package pw.lemmmy.kristpay.krist;

import com.mashape.unirest.http.exceptions.UnirestException;
import lombok.Getter;
import pw.lemmmy.kristpay.KristPay;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Getter
public class Wallet {
	private String privatekey;
	private String address;
	private int balance;
	
	public Wallet(String privatekey) {
		this.privatekey = privatekey;
		this.address = KristAPI.makeKristWalletAddress(privatekey);
	}
	
	public void syncWithNode(Consumer<Boolean> callback) {
		if (!KristPay.INSTANCE.isUp()) callback.accept(false);
		
		KristPay.INSTANCE.getKristClientManager().getKristClient().getAddressBalanceAync(address, newBalance -> {
			balance = newBalance;
			callback.accept(true);
		});
	}
	
	public void transfer(String to, int amount, String metadata, BiConsumer<Boolean, KristTransaction> callback) {
		if (!KristPay.INSTANCE.isUp()) callback.accept(false, null);
		
		try {
			Optional<KristTransaction> opt = KristAPI.makeTransaction(privatekey, to, amount, metadata);
			
			if (opt.isPresent()) {
				callback.accept(true, opt.get());
			} else {
				callback.accept(false, null);
			}
		} catch (UnirestException e) {
			KristPay.INSTANCE.getLogger().error("Error transferring KST from " + address + " to " + to, e);
			callback.accept(false, null);
		}
	}
}
