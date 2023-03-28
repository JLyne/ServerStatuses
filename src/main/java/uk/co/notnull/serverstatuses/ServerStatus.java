package uk.co.notnull.serverstatuses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ServerStatus {
	private static final GsonComponentSerializer gsonComponentSerializer = GsonComponentSerializer.builder().build();

	private final Status status;
	private final int playersOnline;
	private final int playersQueued;
	private final String lockdownReason;
	private final String[] separateLines = {"", ""};
	private String combinedLines = "";

	private transient final Component motd;

	public ServerStatus(Status status, int playersOnline, int playersQueued) {
		this(status, playersOnline, playersQueued, null, null);
	}

	public ServerStatus(Status status, int playersOnline, int playersQueued, Component motd) {
		this(status, playersOnline, playersQueued, motd, null);
	}

	public ServerStatus(Status status, int playersOnline, int playersQueued, Component motd, String lockdownReason) {
		this.status = status;
		this.playersOnline = playersOnline;
		this.playersQueued = playersQueued;
		this.motd = motd;
		this.lockdownReason = lockdownReason;

		prepareComponents();
	}

	private void prepareComponents() {
		Map<String, String> placeholders = new HashMap<>();
		List<String> playerStatus = new ArrayList<>();
		Map<String, ComponentLike> componentPlaceholders = motd != null ?
				Collections.singletonMap("motd",motd) : Collections.emptyMap();

		if (status.isOnline()) {
			playerStatus.add(Messages.get("players.online"));
		}

		if (playersQueued > 0 || !status.isOnline()) {
			playerStatus.add(Messages.get("players.queued"));
		}

		placeholders.put("players", String.join(", ", playerStatus));
		placeholders.put("queued", String.valueOf(playersQueued));
		placeholders.put("online", String.valueOf(playersOnline));

		if(lockdownReason != null) {
			placeholders.put("lockdownreason", lockdownReason);
		}

		String messageKey = status.getMessageKey();
		Component line1 = Messages.getComponent(messageKey + ".line-1", placeholders, componentPlaceholders);
		Component line2 = Messages.getComponent(messageKey + ".line-2", placeholders, componentPlaceholders);

		separateLines[0] = gsonComponentSerializer.serialize(line1);
		separateLines[1] = gsonComponentSerializer.serialize(line2);

		combinedLines = gsonComponentSerializer.serialize(
				Component.empty().append(line1).append(Component.newline()).append(line2));
	}

	public Status getStatus() {
		return status;
	}

	public boolean isOnline() {
		return status.isOnline();
	}

	public int getPlayersOnline() {
		return playersOnline;
	}

	public int getPlayersQueued() {
		return playersQueued;
	}

	public Component getMotd() {
		return motd;
	}

	public String getLockdownReason() {
		return lockdownReason;
	}

	public String[] getSeparateLines() {
		return separateLines;
	}

	public String getCombinedLines() {
		return combinedLines;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ServerStatus that = (ServerStatus) o;
		return isOnline() == that.isOnline() && getPlayersOnline() == that.getPlayersOnline() && getPlayersQueued() == that.getPlayersQueued() && Objects.equals(
				getMotd(), that.getMotd());
	}

	@Override
	public int hashCode() {
		return Objects.hash(isOnline(), getPlayersOnline(), getPlayersQueued(), getMotd());
	}

	@Override
	public String toString() {
		return "ServerStatus{" +
				"status=" + status.toString() +
				", playersOnline=" + playersOnline +
				", playersQueued=" + playersQueued +
				", motd=" + motd +
				'}';
	}
}
