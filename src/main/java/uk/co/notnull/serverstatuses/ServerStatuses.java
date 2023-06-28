package uk.co.notnull.serverstatuses;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


public class ServerStatuses {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	private StatusInformer statusInformer;
	private final ConcurrentHashMap<RegisteredServer, StatusChecker> serverCheckers = new ConcurrentHashMap<>();

	private boolean proxyQueuesEnabled = false;
	private ProxyQueuesHandler proxyQueuesHandler;


	@Inject
	public ServerStatuses(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		Optional<PluginContainer> proxyQueues = proxy.getPluginManager().getPlugin("proxyqueues");
		proxyQueuesEnabled = proxyQueues.isPresent();

		loadConfig();

		if (proxyQueuesEnabled) {
			this.proxyQueuesHandler = new ProxyQueuesHandler(this, proxyQueues.get());
		}
	}

	@Subscribe
	public void onProxyReload(ProxyReloadEvent event) {
		loadConfig();
	}

	private void loadConfig() {
		// Setup config
		loadResource("config.yml");
		loadResource("messages.yml");

		List<RegisteredServer> serversToInform = new ArrayList<>();
		serverCheckers.values().forEach(StatusChecker::destroy);
		serverCheckers.clear();

		try {
			ConfigurationNode configuration = YAMLConfigurationLoader.builder().setFile(
					new File(dataDirectory.toAbsolutePath().toString(), "config.yml")).build().load();

			String secret = configuration.getNode("secret").getString();

			if(secret == null) {
				logger.warn("No secret provided, server status packets will not be sent");
			}

			ConfigurationNode servers = configuration.getNode("servers");

			if (!servers.isVirtual() && servers.isMap()) {
				Map<Object, ? extends ConfigurationNode> children = servers.getChildrenMap();
				children.forEach((Object key, ConfigurationNode child) -> {
					String serverName = key.toString();
					Optional<RegisteredServer> server = proxy.getServer(serverName);
					if (server.isEmpty()) {
						logger.warn("Ignoring unknown server " + serverName);
						return;
					}

					boolean check = child.getNode("check").getBoolean(false);
					boolean inform = child.getNode("inform").getBoolean(false);

					if(check) {
						logger.warn("Adding status checker for " + serverName);
						serverCheckers.computeIfAbsent(server.get(), (k) -> new StatusChecker(server.get(), this));
					}

					if(inform) {
						logger.warn("Adding status informer for " + serverName);
						serversToInform.add(server.get());
					}
				});
			}

			if(statusInformer == null) {
				statusInformer = new StatusInformer(this, secret, serversToInform);
			} else {
				statusInformer.setSecret(secret);
				statusInformer.setServersToInform(serversToInform);
			}
		} catch (IOException e) {
			logger.error("Error loading config.yml");
			e.printStackTrace();
			return;
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

	ProxyServer getProxy() {
		return proxy;
	}

	Logger getLogger() {
		return logger;
	}

	boolean isProxyQueuesEnabled() {
		return proxyQueuesEnabled;
	}

	ProxyQueuesHandler getProxyQueuesHandler() {
		return proxyQueuesHandler;
	}
}
