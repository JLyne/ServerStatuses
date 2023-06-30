package uk.co.notnull.serverstatuses;

import com.mattmalec.pterodactyl4j.client.entities.ClientServer;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mattmalec.pterodactyl4j.client.managers.WebSocketManager;
import com.mattmalec.pterodactyl4j.client.ws.events.AuthSuccessEvent;
import com.mattmalec.pterodactyl4j.client.ws.events.StatusUpdateEvent;
import com.mattmalec.pterodactyl4j.client.ws.events.connection.FailureEvent;
import com.mattmalec.pterodactyl4j.client.ws.events.token.TokenExpiredEvent;
import com.mattmalec.pterodactyl4j.client.ws.events.token.TokenExpiringEvent;
import com.mattmalec.pterodactyl4j.client.ws.hooks.ClientSocketListenerAdapter;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import ninja.leaping.configurate.ConfigurationNode;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import uk.co.notnull.serverstatuses.events.ServerStatusChangeEvent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class StatusChecker extends ClientSocketListenerAdapter {

	private static final MiniMessage miniMessage = MiniMessage.miniMessage();
	private static final TextReplacementConfig newlineRemoval = TextReplacementConfig.builder()
			.match("\n").replacement("").build();

	private final ProxyQueuesHandler proxyQueuesHandler;
	private final ServerStatuses plugin;
	private final ProxyServer proxy;
	private final Logger logger;
	private final PteroClient pteroClient;

	private final RegisteredServer server;
	private Component staticMotd = null;
	private String pterodactylServerId = null;
	private WebSocketManager websocket = null;
	private final AtomicBoolean websocketConnected = new AtomicBoolean(false);
	private ScheduledTask reconnectTask = null;
	private final AtomicInteger reconnectBackoff = new AtomicInteger(1);

	private final ReentrantLock lock = new ReentrantLock();
	private final ScheduledTask pingTask;
	private final AtomicBoolean pinging = new AtomicBoolean(false);
	private final AtomicInteger failedPings = new AtomicInteger(0);
	private @NotNull ServerStatus lastStatus = ServerStatus.builder().build();


	public StatusChecker(RegisteredServer server, ConfigurationNode config, PteroClient pteroClient, ServerStatuses plugin) {
		this.server = server;
		this.plugin = plugin;
		this.logger = plugin.getLogger();
		this.proxy = plugin.getProxy();
		this.pteroClient = pteroClient;
		this.proxyQueuesHandler = plugin.getProxyQueuesHandler();

		loadConfig(config);

		pingTask = plugin.getProxy().getScheduler()
				.buildTask(plugin, () -> this.pingServer(server)).repeat(3, TimeUnit.SECONDS).schedule();
	}

	public void destroy() {
		pingTask.cancel();
		disconnectWebsocket();
		pinging.set(false);
	}

	private void loadConfig(ConfigurationNode config) {
		String motd = config.getNode("motd").getString(null);
		pterodactylServerId = config.getNode("pterodactyl-id").getString(null);
		staticMotd = motd != null ? miniMessage.deserialize(motd) : null;

		if(pterodactylServerId != null) {
			connectWebsocket();
		}
	}

	private void connectWebsocket() {
		pteroClient.retrieveServerByIdentifier(pterodactylServerId).map(ClientServer::getWebSocketBuilder)
               .map(builder -> builder.addEventListeners(this)).executeAsync((builder) -> websocket = builder.build());
	}
	private void disconnectWebsocket() {
		if(reconnectTask != null) {
			reconnectTask.cancel();
		}

		if(websocket != null) {
			try {
				websocket.shutdown();
			} catch (Exception ignored) {
			} finally {
				websocket = null;
			}
		}

		websocketConnected.set(false);
	}

	@Override
    public void onAuthSuccess(AuthSuccessEvent event) {
		websocketConnected.set(true);
		reconnectBackoff.set(1);
    }

	@Override
	public void onStatusUpdate(StatusUpdateEvent event) {
		lock.lock();

		try {
			Status status = Status.fromUtilizationState(event.getState());
			fireChangeEvent(lastStatus.toBuilder().status(status).build());

			if(proxyQueuesHandler != null) {
				if(!proxyQueuesHandler.hasPause(server) && !status.isOnline()) {
					proxyQueuesHandler.pause(server);
				} else if(status.isOnline()) {
					proxyQueuesHandler.unpause(server);
				}
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void onTokenExpiring(TokenExpiringEvent event) {
		logger.warn("Token for pterodactyl websocket connection {} expires soon", server.getServerInfo().getName());
	}

	@Override
	public void onTokenExpired(TokenExpiredEvent event) {
		logger.warn("Token for pterodactyl websocket connection {} has expired", server.getServerInfo().getName());
	}

	@Override
	public void onFailure(FailureEvent event) {
		websocketConnected.set(false);
		logger.warn("Pterodactyl websocket connection for {} failed. Reconnecting in {} seconds...", server.getServerInfo().getName(), reconnectBackoff.get());
		event.getThrowable().printStackTrace();

		int backoffTime = reconnectBackoff.getAndUpdate(x -> Math.min(2 * x, 60));
		reconnectTask = proxy.getScheduler()
				.buildTask(plugin, () -> event.getWebSocketManager().reconnect())
				.delay(backoffTime, TimeUnit.SECONDS).schedule();
	}

	private void pingServer(RegisteredServer server) {
		if(!pinging.get()) {
			pinging.set(true);
			server.ping().exceptionally((e) -> {
				logger.warn("Pinging failed for " + server.getServerInfo().getName() + ": " + e.getMessage());
				return null;
			}).whenCompleteAsync((result, exception) -> {
				handlePingResponse(result);
				pinging.set(false);
			});
		}
	}

	private void handlePingResponse(ServerPing response) {
		ServerStatus status;
		int failed = 0;

		if(!pinging.get()) {
			return;
		}

		int queuedPlayers = proxyQueuesHandler != null ? proxyQueuesHandler.getQueuedPlayers(server) : 0;

		lock.lock();
		try {
			ServerStatus.Builder builder = lastStatus.toBuilder().queued(queuedPlayers).motd(staticMotd);

			if (response == null) {
				failed = failedPings.incrementAndGet();
				builder.players(0);

				if(!websocketConnected.get()) {
					builder.status(Status.OFFLINE);
				}
			} else {
				failedPings.set(0);
				builder.players(response.getPlayers().map(ServerPing.Players::getOnline).orElse(0))
						.motd(staticMotd != null
									  ? staticMotd : response.getDescriptionComponent().replaceText(newlineRemoval));

				if(!websocketConnected.get()) {
					builder.status(Status.ONLINE);
				}
			}

			status = builder.build();

			if (!status.equals(lastStatus)) {
				fireChangeEvent(status);
			}
		} finally {
			lock.unlock();
		}

		// Pause a server's queue if it receives 3 failed pings in a row
		if(proxyQueuesHandler != null && !websocketConnected.get()) {
			if(!proxyQueuesHandler.hasPause(server) && failed >= 3) {
				proxyQueuesHandler.pause(server);
			} else if(failed == 0) {
				proxyQueuesHandler.unpause(server);
			}
		}
	}

	private void fireChangeEvent(ServerStatus newStatus) {
		proxy.getEventManager().fireAndForget(new ServerStatusChangeEvent(server, newStatus, lastStatus));
		lastStatus = newStatus;
	}
}
