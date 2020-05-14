package pw.lemmmy.kristpay.commands;

import lombok.val;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandMessageFormatting;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.CommandElement;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.service.economy.EconomyService;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.economy.transaction.TransferResult;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;
import pw.lemmmy.kristpay.KristPay;
import pw.lemmmy.kristpay.database.TransactionLogEntry;
import pw.lemmmy.kristpay.database.TransactionType;
import pw.lemmmy.kristpay.economy.KristAccount;
import pw.lemmmy.kristpay.krist.MasterWallet;
import pw.lemmmy.kristpay.krist.NameNotFoundException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.spongepowered.api.command.args.GenericArguments.*;
import static org.spongepowered.api.text.Text.builder;
import static org.spongepowered.api.text.Text.of;
import static org.spongepowered.api.text.format.TextColors.*;

public class CommandPay implements CommandExecutor {
	public static final String KRIST_TRANSFER_PATTERN = "^(?:[a-f0-9]{10}|k[a-z0-9]{9}|(?:[a-z0-9-_]{1,32}@)?[a-z0-9]{1,64}\\.kst)$";
	
	private static final CommandElement TARGET_ELEMENT = firstParsing(
		user(of("user")),
		string(of("address"))
	);
	
	private static final CommandElement AMOUNT_ELEMENT = integer(of("amount"));
	
	public static final CommandSpec SPEC = CommandSpec.builder()
		.description(of("Sends Krist to another user or address."))
		.permission("kristpay.command.pay.base")
		.arguments(
			flags().flag("k").buildWith(none()),
			TARGET_ELEMENT,
			AMOUNT_ELEMENT,
			optional(remainingJoinedStrings(of("meta")))
		)
		.executor(new CommandPay())
		.build();
	
	private static final EconomyService ECONOMY_SERVICE = KristPay.INSTANCE.getEconomyService();
	
	private static final double WARN_LIMIT_PERCENTAGE = 0.5;
	private static final int WARN_LIMIT_HARD = 1000;
	
	@Override
	public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
		if (!(src instanceof User)) throw new CommandException(of("Must be ran by a user."));
		User owner = (User) src;
		UniqueAccount ownerAccount = ECONOMY_SERVICE.getOrCreateAccount(owner.getUniqueId())
			.orElseThrow(() -> new CommandException(of("Failed to find that account.")));
		
		int amount = args.<Integer>getOne("amount")
			.orElseThrow(() -> new CommandException(of("Must specify a valid amount to pay.")));
		if (amount <= 0) throw new CommandException(of("Amount must be positive."));
		
		int balance = ownerAccount.getBalance(KristPay.INSTANCE.getCurrency()).intValue();
		if (balance - amount < 0)
			throw new CommandException(of("You don't have enough funds for this transaction."));
		
		String meta = args.<String>getOne("meta").orElse("");
		
		if (args.hasAny("user") && !args.hasAny("k")) {
			User target = args.<User>getOne("user")
				.orElseThrow(() -> new CommandException(of("Must specify a valid user or address to pay to.")));
			KristAccount targetAccount = (KristAccount) ECONOMY_SERVICE.getOrCreateAccount(target.getUniqueId())
				.orElseThrow(() -> new CommandException(of("Failed to find the target user's account.")));
			
			if (target.getName().toLowerCase().matches(KRIST_TRANSFER_PATTERN)) {
				src.sendMessage(builder()
					.append(of(TextColors.GOLD, "Warning: "))
					.append(of(TextColors.YELLOW, "The target you specified is both a valid user and a valid " +
						"Krist address. This command prefers users by default. If you wish to transfer to the address" +
						" instead, add the "))
					.append(of(GREEN, "-k "))
					.append(of(TextColors.YELLOW, "flag. "))
					.build()
				);
			}
			
			return checkAmount(src, balance, amount,
				() -> payUser(src, owner, ownerAccount, target, targetAccount, amount, meta));
		} else {
			String target = args.<String>getOne("address")
				.orElseThrow(() -> new CommandException(of("Must specify a valid user or address to pay to.")))
				.toLowerCase();
			
			if (!target.matches(KRIST_TRANSFER_PATTERN))
				throw new CommandException(of("Must specify a valid user or address to pay to."));
			
			return checkAmount(src, balance, amount,
				() -> payAddress(src, owner, ownerAccount, target, amount, meta));
		}
	}
	
	private CommandResult checkAmount(CommandSource src, int balance, int amount, CommandCallable onAccept) throws CommandException {
		Text message = null;
		
		if (amount == balance) message = of(TextColors.YELLOW, "(your entire balance!)");
		else if (amount >= balance * WARN_LIMIT_PERCENTAGE) message = of(TextColors.YELLOW, "(more than half your balance!)");
		else if (amount >= WARN_LIMIT_HARD) {
			message = builder()
				.append(of(TextColors.YELLOW, "(more than "))
				.append(CommandHelpers.formatKrist(WARN_LIMIT_HARD, true))
				.append(of(TextColors.YELLOW, "!)"))
				.build();
		}
		
		if (message != null) {
			src.sendMessage(builder()
				.append(of(TextColors.GOLD, "Warning: "))
				.append(of(TextColors.YELLOW, "You're about to make a very large transaction "))
				.append(message)
				.append(of(TextColors.YELLOW, ". "))
				.append(builder()
					.append(of(TextColors.AQUA, "Click here"))
					.onHover(TextActions.showText(builder()
						.append(of(RED, "Confirm transaction "))
						.append(of(DARK_RED, "(dangerous!)"))
						.build()))
					.onClick(TextActions.executeCallback(src2 -> {
						try {
							onAccept.call();
						} catch (CommandException e) {
							Text eText = e.getText();
							if (eText != null) src2.sendMessage(CommandMessageFormatting.error(eText));
						}
					}))
					.build())
				.append(of(TextColors.YELLOW, " to confirm this transaction."))
				.build());
			
			return CommandResult.empty();
		} else {
			return onAccept.call();
		}
	}
	
	private CommandResult payUser(CommandSource src, User owner, UniqueAccount ownerAccount, User target,
								  UniqueAccount targetAccount, int amount, String meta) throws CommandException {
		TransferResult result = ownerAccount.transfer(
			targetAccount,
			KristPay.INSTANCE.getCurrency(),
			BigDecimal.valueOf(amount),
			Sponge.getCauseStackManager().getCurrentCause()
		);
		
		new TransactionLogEntry()
			.setMetaMessage(meta)
			.setTransferResult(result)
			.setFromAccount(ownerAccount)
			.setToAccount(targetAccount)
			.setAmount(amount)
			.addAsync();
		
		switch (result.getResult()) {
			case SUCCESS:
				if (KristPay.INSTANCE.getPrometheusManager() != null)
					KristPay.INSTANCE.getPrometheusManager().getTransactionsReporter().incrementTransfers(amount);
				
				src.sendMessage(
					builder()
						.append(of(DARK_GREEN, "Success! "))
						.append(of(GREEN, "Transferred "))
						.append(CommandHelpers.formatKrist(result.getAmount(), true))
						.append(of(GREEN, " to player "))
						.append(of(TextColors.YELLOW, target.getName()))
						.append(of(GREEN, "."))
						.build()
				);
				
				Optional<Player> targetPlayerOpt = target.getPlayer();
				
				if (targetPlayerOpt.isPresent()) {
					Text.Builder builder = builder()
						.append(of(GREEN, "You have received "))
						.append(CommandHelpers.formatKrist(result.getAmount(), true))
						.append(of(GREEN, " from player "))
						.append(of(TextColors.YELLOW, owner.getName()))
						.append(of(GREEN, "."));
					
					if (meta != null && !meta.isEmpty()) {
						Text message = TextSerializers.FORMATTING_CODE.deserialize(meta);
						
						builder.append(of("\n"))
							.append(of(DARK_GREEN, "Message: "))
							.append(message);
					}
					
					targetPlayerOpt.get().sendMessage(builder.build());
				} else if (targetAccount instanceof KristAccount) {
					KristAccount targetKristAccount = (KristAccount) targetAccount;
					targetKristAccount.setUnseenTransfer(targetKristAccount.getUnseenTransfer() + amount);
					KristPay.INSTANCE.getAccountDatabase().save();
				}
				
				return CommandResult.success();
			case ACCOUNT_NO_FUNDS:
				throw new CommandException(of("You don't have enough funds for this transaction."));
			default:
				src.sendMessage(
					builder()
						.append(of(RED, "Transaction failed ("))
						.append(of(DARK_RED, result.getResult().toString()))
						.append(of(RED, ")."))
						.build()
				);
				return CommandResult.empty();
		}
	}
	
	private CommandResult payAddress(CommandSource src, User owner, UniqueAccount ownerAccount, String target,
									 int amount, String meta) throws CommandException {
		TransactionResult result = ownerAccount.withdraw(
			KristPay.INSTANCE.getCurrency(),
			BigDecimal.valueOf(amount),
			Sponge.getCauseStackManager().getCurrentCause()
		);
		
		if (result.getResult() != ResultType.SUCCESS) {
			new TransactionLogEntry()
				.setTransactionResult(result)
				.setFromAccount(ownerAccount)
				.setDestAddress(target)
				.setAmount(amount)
				.addAsync();
		}
		
		switch (result.getResult()) {
			case SUCCESS:
				MasterWallet masterWallet = KristPay.INSTANCE.getMasterWallet();
				
				StringBuilder metadata = new StringBuilder()
					// return address
					.append("return=")
					.append(owner.getName().toLowerCase())
					.append("@")
					.append(KristPay.INSTANCE.getConfig().getMasterWallet().getPrimaryDepositName())
					// username
					.append(";")
					.append("username=")
					.append(owner.getName());
				
				// custom meta (optional)
				if (meta != null && !meta.isEmpty()) {
					metadata.append(";")
						.append(meta);
				}
				
				// remove invalid characters and shorten it to 255 chars
				String cleanMetadata = metadata.toString().replaceAll("[^\\x20-\\x7F\\n]", "");
				cleanMetadata = cleanMetadata.substring(0, Math.min(cleanMetadata.length(), 255));
				
				// if any cleaning was applied, warn the user
				if (!cleanMetadata.equals(metadata.toString())) {
					src.sendMessage(builder()
						.append(of(TextColors.GOLD, "Warning: "))
						.append(of(TextColors.YELLOW, "Your metadata contained invalid characters, or was too " +
							"long. It was automatically cleaned up.")).build());
				}
				
				masterWallet.transfer(target, amount, cleanMetadata).handle((tx, ex) -> {
					val entry = new TransactionLogEntry()
						.setSuccess(ex == null)
						.setError(ex != null ? "Transaction failed." : null)
						.setType(TransactionType.WITHDRAW)
						.setFromAccount(ownerAccount)
						.setDestAddress(target)
						.setTransaction(tx)
						.setAmount(amount);
					
					if (ex != null) {
						if (ex instanceof NameNotFoundException || ex.getCause() instanceof NameNotFoundException) {
							src.sendMessage(CommandMessageFormatting.error(of("Name not found! Did you type the address correctly?")));
							entry.setError("Name not found! Did you type the address correctly?");
						} else {
							KristPay.INSTANCE.getLogger().error("Error in transaction", ex);
							src.sendMessage(CommandMessageFormatting.error(of("Transaction failed. Try again later, or contact an admin.")));
						}
						
						// refund their transaction
						TransactionResult result2 = ownerAccount.deposit(
							KristPay.INSTANCE.getCurrency(),
							BigDecimal.valueOf(amount),
							Cause.of(EventContext.empty(), this)
						);
						
						if (result2.getResult() != ResultType.SUCCESS) {
							KristPay.INSTANCE.getLogger().error(
								"Error refunding failed transaction ({} KST -> {}): {}",
								amount, ownerAccount.getUniqueId(), result2.getResult()
							);
							
							src.sendMessage(CommandMessageFormatting.error(of(
								"An error occurred while trying to refund your transaction: " + result2.getResult().toString() + "\n" +
								"Please contact a member of staff."
							)));
						}
					} else {
						if (KristPay.INSTANCE.getPrometheusManager() != null)
							KristPay.INSTANCE.getPrometheusManager().getTransactionsReporter().incrementWithdrawals(amount);
						
						Text.Builder builder = builder()
							.append(of(DARK_GREEN, "Success! "))
							.append(of(GREEN, "Transferred "))
							.append(CommandHelpers.formatKrist(result.getAmount(), true))
							.append(of(GREEN, " to address "))
							.append(CommandHelpers.formatAddress(target));
						
						if (!target.equalsIgnoreCase(tx.getTo())) {
							builder.append(of(GREEN, " ("))
								.append(CommandHelpers.formatAddress(tx.getTo()))
								.append(of(GREEN, ")"));
						}
						
						src.sendMessage(builder.append(of(GREEN, ".")).build());
					}
					
					entry.addAsync();
					return tx;
				});
				
				return CommandResult.success();
			case ACCOUNT_NO_FUNDS:
				throw new CommandException(of("You don't have enough funds for this transaction."));
			default:
				throw new CommandException(
					builder()
						.append(of(RED, "Transaction failed ("))
						.append(of(DARK_RED, result.getResult().toString()))
						.append(of(RED, ")."))
						.build()
				);
		}
	}
	
	@FunctionalInterface
	private interface CommandCallable {
		CommandResult call() throws CommandException;
	}
}