package uk.co.notnull.serverstatuses;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.TextReplacementConfig;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StatusChecker {
	private static final TextReplacementConfig newlineRemoval = TextReplacementConfig.builder()
			.match("\n").replacement("").build();
	private final RegisteredServer server;

	private final ProxyQueuesHandler proxyQueuesHandler;
	private final ProxyServer proxy;
	private final Logger logger;

	private final ScheduledTask pingTask;
	private ServerStatus lastStatus = null;

	private final AtomicBoolean pinging = new AtomicBoolean(false);
	private final AtomicInteger failedPings = new AtomicInteger(0);


	public StatusChecker(RegisteredServer server, ServerStatuses plugin) {
		this.server = server;
		this.logger = plugin.getLogger();
		this.proxy = plugin.getProxy();
		this.proxyQueuesHandler = plugin.getProxyQueuesHandler();

		pingTask = plugin.getProxy().getScheduler()
				.buildTask(plugin, () -> this.pingServer(server)).repeat(3, TimeUnit.SECONDS).schedule();
	}

	public void destroy() {
		pingTask.cancel();
		pinging.set(false);
	}

	private void pingServer(RegisteredServer server) {
		if(!pinging.get()) {
			logger.info("Here");
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
		AtomicBoolean changed = new AtomicBoolean(false);
		int failed = 0;

		if(!pinging.get()) {
			return;
		}

		int queuedPlayers = proxyQueuesHandler != null ? proxyQueuesHandler.getQueuedPlayers(server) : 0;

		if (response == null) {
			status = ServerStatus.builder().queued(queuedPlayers).build();
		} else {
			status = ServerStatus.builder()
					.status(Status.ONLINE)
					.players(response.getPlayers().map(ServerPing.Players::getOnline).orElse(0))
					.queued(queuedPlayers)
					.motd(response.getDescriptionComponent().replaceText(newlineRemoval))
					.build();
		}

		changed.set(!status.equals(lastStatus));

		if(status.isOnline()) {
			failedPings.set(0);
		} else {
			failed = failedPings.incrementAndGet();
		}

		if (changed.get()) {
			proxy.getEventManager().fireAndForget(new ServerStatusChangeEvent(server, status, lastStatus));
			lastStatus = status;
		}

		// Pause a server's queue if it receives 3 failed pings in a row
		if(proxyQueuesHandler != null) {
			if(!proxyQueuesHandler.hasPause(server) && failed >= 3) {
				proxyQueuesHandler.pause(server);
			} else if(failed == 0) {
				proxyQueuesHandler.unpause(server);
			}
		}
	}
}
