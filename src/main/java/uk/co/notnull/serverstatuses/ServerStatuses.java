package uk.co.notnull.serverstatuses;

import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class ServerStatuses {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	private final ConcurrentHashMap.KeySetView<RegisteredServer, Boolean> ongoingPings = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<String, ServerStatus> serverStatuses = new ConcurrentHashMap<>();

	private boolean proxyQueuesEnabled = false;
	private ProxyQueuesHandler proxyQueuesHandler;

	private String secret;
	private final List<RegisteredServer> serversToPing = new ArrayList<>();
	private final List<RegisteredServer> serversToInform = new ArrayList<>();
	private final MinecraftChannelIdentifier statusChannel = MinecraftChannelIdentifier
			.create("serverstatus", "status");

	@Inject
	public ServerStatuses(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;

		loadConfig();
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		Optional<PluginContainer> proxyQueues = proxy.getPluginManager().getPlugin("proxyqueues");
		proxyQueuesEnabled = proxyQueues.isPresent();

		if (proxyQueuesEnabled) {
			this.proxyQueuesHandler = new ProxyQueuesHandler(proxyQueues.get());
		}

		proxy.getScheduler().buildTask(this, () -> {
			for (RegisteredServer server : serversToPing) {
				this.pingServer(server);
			}
		}).repeat(3, TimeUnit.SECONDS).schedule();
	}

	@Subscribe
	public void onProxyReload(ProxyReloadEvent event) {
		loadConfig();
	}

	@Subscribe
	public void onServerJoined(ServerPostConnectEvent event) {
		RegisteredServer server = event.getPlayer().getCurrentServer().map(ServerConnection::getServer)
				.orElse(null);

		if (server != null && serversToInform.contains(server) && server.getPlayersConnected().size() == 1) {
			sendStatusPacketToServer(server);
		}
	}

	private void pingServer(RegisteredServer server) {
		logger.info("Pinging " + server.getServerInfo().getName() + "?");
		if(!ongoingPings.contains(server)) {
			logger.info("Pinging " + server.getServerInfo().getName() + "!");
			logger.info("Pinging survival " + server.getServerInfo().getName());
			ongoingPings.add(server);
			server.ping().exceptionally((e) -> {
				logger.warn("Pinging failed for " + server.getServerInfo().getName() + ": " + e.getMessage());
				return null;
			}).whenComplete((result, exception) -> {
				handlePingResponse(server, result);
				ongoingPings.remove(server);
			});
		}
	}

	private void handlePingResponse(RegisteredServer server, ServerPing response) {
		ServerStatus status;
		AtomicBoolean changed = new AtomicBoolean(false);

		if (!serversToPing.contains(server)) {
			return;
		}

		int queuedPlayers = proxyQueuesEnabled ? proxyQueuesHandler.getQueuedPlayers(server) : 0;

		if (response == null) {
			status = new ServerStatus(Status.OFFLINE, 0, queuedPlayers);
		} else {
			status = new ServerStatus(Status.ONLINE, response.getPlayers().map(ServerPing.Players::getOnline)
					.orElse(0), queuedPlayers, response.getDescriptionComponent());
		}

		serverStatuses.compute(server.getServerInfo().getName(), (String name, ServerStatus value) -> {
			logger.info("handlePingResponse " + server.getServerInfo().getName() + "\n" + (value != null ? value.toString() : "null") + "\n" + status + "\n" + !status.equals(value));
			changed.set(!status.equals(value));
			return status;
		});

		if (changed.get()) {
			logger.info("Sending status packet as " + server.getServerInfo().getName() + " has changed");
			sendStatusPacket();
		}
	}

	private void sendStatusPacket() {
		for (RegisteredServer server : serversToInform) {
			sendStatusPacketToServer(server);
		}
	}

	private void sendStatusPacketToServer(RegisteredServer server) {
		Optional<Player> player = server.getPlayersConnected().stream().findFirst();

		if (player.isPresent() && player.get().getCurrentServer().isPresent()) {
			ServerConnection connection = player.get().getCurrentServer().get();

			try {
				final byte[] byteKey = secret.getBytes(StandardCharsets.UTF_8);
				Gson gson = new GsonBuilder().create();
				Mac hmac = Mac.getInstance("HmacSHA512");

				SecretKeySpec keySpec = new SecretKeySpec(byteKey, "HmacSHA512");
				hmac.init(keySpec);

				String statusJson = gson.toJson(serverStatuses);

				byte[] macData = hmac.doFinal(statusJson.getBytes(StandardCharsets.UTF_8));

				Map<String, Object> payload = Map.of(
						"hmac", byteArrayToHex(macData),
						"servers", statusJson
				);

				connection.sendPluginMessage(statusChannel, gson.toJson(payload).getBytes());
			} catch (NoSuchAlgorithmException | InvalidKeyException e) {
				logger.error("Failed to generate status packet for " + server.getServerInfo().getName());
				e.printStackTrace();
			}
		}
	}

	private boolean loadConfig() {
		// Setup config
		loadResource("config.yml");
		loadResource("messages.yml");

		serversToInform.clear();
		serversToPing.clear();
		serverStatuses.clear();

		try {
			ConfigurationNode configuration = YAMLConfigurationLoader.builder().setFile(
					new File(dataDirectory.toAbsolutePath().toString(), "config.yml")).build().load();

			secret = configuration.getNode("secret").getString();
			ConfigurationNode toPing = configuration.getNode("servers-to-ping");
			ConfigurationNode toInform = configuration.getNode("servers-to-inform");

			if (!toPing.isEmpty()) {
				if (toPing.isList()) {
					List<? extends ConfigurationNode> children = toPing.getChildrenList();

					children.forEach((ConfigurationNode child) -> {
						if (!child.isEmpty() && !child.isMap() && !child.isList()) {
							String serverName = child.getString(null);
							Optional<RegisteredServer> server = proxy.getServer(serverName);

							if (server.isPresent()) {
								serversToPing.add(server.get());
							} else {
								logger.warn("Ignoring unknown server " + serverName);
							}
						}
					});
				}
			}

			if (!toInform.isEmpty()) {
				if (toInform.isList()) {
					List<? extends ConfigurationNode> children = toInform.getChildrenList();

					children.forEach((ConfigurationNode child) -> {
						if (!child.isEmpty() && !child.isMap() && !child.isList()) {
							String serverName = child.getString(null);
							Optional<RegisteredServer> server = proxy.getServer(serverName);

							if (server.isPresent()) {
								serversToInform.add(server.get());
							} else {
								logger.warn("Ignoring unknown server " + serverName);
							}
						}
					});
				}
			}

		} catch (IOException e) {
			logger.error("Error loading config.yml");
			e.printStackTrace();
			return false;
		}

		//Message config
        ConfigurationNode messagesConfiguration;

        try {
			messagesConfiguration = YAMLConfigurationLoader.builder().setFile(
					new File(dataDirectory.toAbsolutePath().toString(), "messages.yml")).build().load();
		    Messages.set(messagesConfiguration);
		} catch (IOException e) {
			logger.error("Error loading messages.yml");
		}

		return true;
	}

	private void loadResource(String resource) {
		File folder = dataDirectory.toFile();

		if (!folder.exists()) {
			folder.mkdir();
		}

		File resourceFile = new File(dataDirectory.toFile(), resource);

		try {
			if (!resourceFile.exists()) {
				resourceFile.createNewFile();

				try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
					 OutputStream out = new FileOutputStream(resourceFile)) {
					ByteStreams.copy(in, out);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static String byteArrayToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for (byte b : a)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}
}
