package uk.co.notnull.serverstatuses;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.mattmalec.pterodactyl4j.PteroBuilder;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
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
import uk.co.notnull.messageshelper.Message;
import uk.co.notnull.messageshelper.MessagesHelper;

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
	private PteroClient pterodactylClient = null;

	private static final MessagesHelper messagesHelper = MessagesHelper.getInstance();

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
		boolean proxyQueuesEnabled = proxyQueues.isPresent();

		if (proxyQueuesEnabled) {
			this.proxyQueuesHandler = new ProxyQueuesHandler(this, proxyQueues.get());
		}

		initCommand();
		loadConfig();
	}

	@Subscribe
	public void onProxyReload(ProxyReloadEvent event) {
		loadConfig();
	}

	private void initCommand() {
		CommandManager commandManager = getProxy().getCommandManager();

        CommandMeta reloadMeta = commandManager.metaBuilder("ssreload")
            .plugin(this)
            .build();

		LiteralCommandNode<CommandSource> reloadNode = LiteralArgumentBuilder
            .<CommandSource>literal("ssreload")
            .requires(source -> source.hasPermission("serverstatuses.reload"))
            .executes(context -> {
                loadConfig();
				messagesHelper.send(context.getSource(), Message.builder("reloaded").build());
				return Command.SINGLE_SUCCESS;
            }).build();

        // BrigadierCommand implements Command
        commandManager.register(reloadMeta, new BrigadierCommand(reloadNode));
	}

	private void loadConfig() {
		// Setup config
		loadResource("config.yml");
		loadResource("messages.yml");


		List<RegisteredServer> serversToInform = new ArrayList<>();
		serverCheckers.values().forEach(StatusChecker::destroy);
		serverCheckers.clear();

		try {
			messagesHelper.loadMessages(new File(dataDirectory.toAbsolutePath().toString(), "messages.yml"));
		} catch (IOException e) {
			logger.error("Error loading messages.yml");
		}

		try {
			ConfigurationNode configuration = YAMLConfigurationLoader.builder().setFile(
					new File(dataDirectory.toAbsolutePath().toString(), "config.yml")).build().load();

			String pterodactylUrl = configuration.getNode("pterodactyl", "api-url").getString(null);
			String pterodactylKey = configuration.getNode("pterodactyl", "api-key").getString(null);

			if(pterodactylUrl != null && pterodactylKey != null) {
				logger.info("Using pterodactyl");
				pterodactylClient = PteroBuilder.createClient(pterodactylUrl, pterodactylKey);
			}

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
						serverCheckers.computeIfAbsent(server.get(), (k) ->
								new StatusChecker(server.get(), child, pterodactylClient, this));
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

	static MessagesHelper getMessagesHelper() {
		return messagesHelper;
	}

	ProxyServer getProxy() {
		return proxy;
	}

	Logger getLogger() {
		return logger;
	}

	ProxyQueuesHandler getProxyQueuesHandler() {
		return proxyQueuesHandler;
	}
}
