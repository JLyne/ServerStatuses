package uk.co.notnull.serverstatuses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class StatusInformer {
	private static final MinecraftChannelIdentifier statusChannel = MinecraftChannelIdentifier
			.create("serverstatus", "status");
	private final ServerStatuses plugin;
	private byte[] secret = null;
	private List<RegisteredServer> serversToInform;
	private final ConcurrentHashMap<String, ServerStatus> serverStatuses = new ConcurrentHashMap<>();

	public StatusInformer(ServerStatuses plugin, String secret, List<RegisteredServer> servers) {
		this.plugin = plugin;
		setSecret(secret);
		setServersToInform(servers);
		plugin.getProxy().getEventManager().register(plugin, this);
	}

	@Subscribe
	public void onServerStatusChange(ServerStatusChangeEvent event) {
		serverStatuses.compute(event.getServer().getServerInfo().getName(), (k, v) -> event.getStatus());
		sendStatusPacket();
	}

	@Subscribe
	public void onServerJoined(ServerPostConnectEvent event) {
		RegisteredServer server = event.getPlayer().getCurrentServer().map(ServerConnection::getServer)
				.orElse(null);

		if (server != null && serversToInform.contains(server) && server.getPlayersConnected().size() == 1) {
			sendStatusPacket(server);
		}
	}

	private void sendStatusPacket() {
		byte[] payload = generatePayload();

		for (RegisteredServer server : serversToInform) {
			sendStatusPacketToServer(server, payload);
		}
	}

	private void sendStatusPacket(RegisteredServer server) {
		byte[] payload = generatePayload();

		if(payload != null) {
			sendStatusPacketToServer(server, payload);
		}
	}

	private void sendStatusPacketToServer(RegisteredServer server, byte[] payload) {
		Optional<Player> player = server.getPlayersConnected().stream().findFirst();

		if (player.isPresent() && player.get().getCurrentServer().isPresent()) {
			ServerConnection connection = player.get().getCurrentServer().get();

			connection.sendPluginMessage(statusChannel, payload);
		}
	}

	private byte[] generatePayload() {
		byte[] payloadBytes;

		if(secret == null) {
			return null;
		}

		try {
			Gson gson = new GsonBuilder().create();
			Mac hmac = Mac.getInstance("HmacSHA512");

			SecretKeySpec keySpec = new SecretKeySpec(secret, "HmacSHA512");
			hmac.init(keySpec);

			String statusJson = gson.toJson(serverStatuses);

			byte[] macData = hmac.doFinal(statusJson.getBytes(StandardCharsets.UTF_8));

			Map<String, Object> payload = Map.of(
					"hmac", byteArrayToHex(macData),
					"servers", statusJson
			);

			payloadBytes = gson.toJson(payload).getBytes();
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			plugin.getLogger().error("Failed to generate status packet payload");
			e.printStackTrace();
			return null;
		}

		return payloadBytes;
	}

	private static String byteArrayToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for (byte b : a)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}

	public void setSecret(String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	public void setServersToInform(List<RegisteredServer> serversToInform) {
		this.serversToInform = serversToInform;
	}
}
