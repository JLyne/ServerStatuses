package uk.co.notnull.serverstatuses;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import uk.co.notnull.proxyqueues.api.ProxyQueues;
import uk.co.notnull.proxyqueues.api.QueueType;
import uk.co.notnull.proxyqueues.api.queues.ProxyQueue;

public class ProxyQueuesHandler {
	private final ServerStatuses plugin;
	private final ProxyQueues proxyQueues;

	public ProxyQueuesHandler(ServerStatuses plugin, PluginContainer proxyQueues) {
		this.plugin = plugin;
		this.proxyQueues = (ProxyQueues) proxyQueues.getInstance().get();
	}

	public int getQueuedPlayers(RegisteredServer server) {
		ProxyQueue queue = proxyQueues.getQueueHandler().getQueue(server);

		if (queue == null) {
			return 0;
		}

		return queue.getQueueSize(QueueType.NORMAL) + queue.getQueueSize(QueueType.PRIORITY)
				+ queue.getQueueSize(QueueType.STAFF);
	}

	public boolean hasPause(RegisteredServer server) {
		ProxyQueue queue = proxyQueues.getQueueHandler().getQueue(server);

		if (queue == null) {
			return false;
		}

		return queue.hasPause(plugin);
	}

	public void pause(RegisteredServer server) {
		ProxyQueue queue = proxyQueues.getQueueHandler().getQueue(server);

		if (queue == null) {
			return;
		}

		queue.addPause(plugin, "Server is offline");
	}

	public void unpause(RegisteredServer server) {
		ProxyQueue queue = proxyQueues.getQueueHandler().getQueue(server);

		if (queue == null) {
			return;
		}

		queue.removePause(plugin);
	}
}

