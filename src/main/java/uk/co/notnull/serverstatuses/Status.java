package uk.co.notnull.serverstatuses;

public enum Status {
	OFFLINE(false, "statuses.offline"),
	ONLINE(true, "statuses.online"),
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
}
