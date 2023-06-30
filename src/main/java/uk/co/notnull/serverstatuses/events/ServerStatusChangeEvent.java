package uk.co.notnull.serverstatuses.events;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.Nullable;
import uk.co.notnull.serverstatuses.ServerStatus;

public class ServerStatusChangeEvent {
	private final RegisteredServer server;
	private final ServerStatus status;
	private final @Nullable ServerStatus previousStatus;

	public ServerStatusChangeEvent(RegisteredServer server, ServerStatus status, @Nullable ServerStatus previousStatus) {
		this.server = server;
		this.status = status;
		this.previousStatus = previousStatus;
	}

	public RegisteredServer getServer() {
		return server;
	}

	public ServerStatus getStatus() {
		return status;
	}

	public ServerStatus getPreviousStatus() {
		return previousStatus;
	}
}
