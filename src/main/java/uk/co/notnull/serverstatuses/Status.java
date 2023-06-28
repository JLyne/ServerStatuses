package uk.co.notnull.serverstatuses;

import com.mattmalec.pterodactyl4j.UtilizationState;

public enum Status {
	OFFLINE(false, "statuses.offline"),
	ONLINE(true, "statuses.online"),
	STOPPING(false, "statuses.stopping"),
	STARTING(false, "statuses.starting"),
	LOCKDOWN(true, "statuses.lockdown");

	private final boolean online;
	private final String messageKey;

	Status(boolean online, String messageKey) {
		this.online = online;
		this.messageKey = messageKey;
	}

	public boolean isOnline() {
		return online;
	}

	public String getMessageKey() {
		return messageKey;
	}

	public static Status fromUtilizationState(UtilizationState state) {
		switch(state) {
			case STARTING -> {
				return STARTING;
			}
			case STOPPING ->  {
				return STOPPING;
			}
			case RUNNING -> {
				return ONLINE;
			}
			default -> {
				return OFFLINE;
			}
		}
	}
}
