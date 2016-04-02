package com.supaham.commandpipe;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Ali on 04/04/2015.
 */
public class CommandPipe {

  private final Plugin plugin;
  private final Configuration config;

  private final ConcurrentHashMap<UUID, Integer> limits = new ConcurrentHashMap<>();
  private final Cache<UUID, String> commands;
  private final Listener listener = new PipeListener();

  public CommandPipe(Plugin plugin, Configuration config) {
    this(plugin, config, null);
  }

  public CommandPipe(Plugin plugin, Configuration config, CommandPipe copyFrom) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin cannot be null.");
    this.config = Preconditions.checkNotNull(config, "configuration cannot be null.");
    this.commands = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES)
        // invalidate limits map
        .removalListener(new RemovalListener<Object, Object>() {
          @Override
          public void onRemoval(RemovalNotification<Object, Object> notification) {
            if (notification.getKey() != null) {
              limits.remove(notification.getKey());
            }
          }
        }).build();
    if (copyFrom != null) {
      // Add already existing commands
      for (Entry<UUID, String> entry : copyFrom.commands.asMap().entrySet()) {
        this.commands.put(entry.getKey(), entry.getValue());
      }
      this.limits.putAll(copyFrom.limits);
    }
    init();
  }

  public void init() {
    if (this.config.listeningToChat || this.config.listeningToCommands) {
      plugin.getServer().getPluginManager().registerEvents(this.listener, plugin);
    }
  }

  public void destroy() {
    HandlerList.unregisterAll(this.listener);
  }

  public String handleMessage(Player player, String string) {
    if (string.isEmpty()) {
      return string;
    }
    
    // Handle escaping
    Replacer replacer = new Replacer(config.escapeRegex, string);
    if (replacer.matches()) {
      // we dont want to handle anything if the message matches the escapeRegex
      return replacer.toString();
    }

    final String previous = this.commands.getIfPresent(player.getUniqueId());
    replacer = new Replacer(config.regex, string);
    if (replacer.matches()) {
      string = replacer.toString();
    }
    if (!replacer.matches() // this message doesn't match the piping regex.
        || string.isEmpty()) { // the player is done typing their message
      if (previous == null) { // this player hasn't piped anything
        return string;
      }
      // The player is done piping, lets execute their whole message.
      string = previous + string;
      this.commands.invalidate(player.getUniqueId());
      player.chat(string);
      return null;
    }
    // Event calling and string replacement
    CommandPipeEvent event = new CommandPipeEvent(player, previous, string);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      return string;
    }
    string = event.getCurrentMessage();
    Preconditions.checkNotNull(string, "final string cannot be null.");
    Preconditions.checkArgument(!string.isEmpty(), "final string cannot be empty.");

    // update limit
    Integer integer = this.limits.get(player.getUniqueId());
    this.limits.put(player.getUniqueId(), integer == null ? 1 : ++integer);

    this.commands.put(player.getUniqueId(), (previous != null ? previous : "") + string);
    return null;
  }

  public Plugin getPlugin() {
    return plugin;
  }

  public Cache<UUID, String> getCommands() {
    return commands;
  }

  private final class PipeListener implements Listener {

    @EventHandler
    public void onCommand(PlayerQuitEvent event) {
      commands.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
      if (config.listeningToChat) {
        String s = handleMessage(event.getPlayer(), event.getMessage());
        if (s == null) {
          event.setCancelled(true);
        } else {
          event.setMessage(s);
        }
      }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
      if (config.listeningToCommands) {
        String s = handleMessage(event.getPlayer(), event.getMessage());
        if (s == null) {
          event.setCancelled(true);
        } else {
          event.setMessage(s);
        }
      }
    }
  }

  public static class Configuration {

    private Pattern escapeRegex = Pattern.compile(".*(\\\\\\|)$"); // \\\|$
    private Pattern regex = Pattern.compile(".*(\\|)$"); // \|$
    private Map<String, Integer> limitGroups = new HashMap<>();
    private boolean listeningToChat = true;
    private boolean listeningToCommands = true;

    {
      limitGroups.put("default", 3);
    }

    public Pattern getEscapeRegex() {
      return escapeRegex;
    }

    public void setEscapeRegex(Pattern escapeRegex) {
      this.escapeRegex = escapeRegex;
    }

    public Pattern getRegex() {
      return regex;
    }

    public void setRegex(Pattern regex) {
      this.regex = regex;
    }

    public Map<String, Integer> getLimitGroups() {
      return limitGroups;
    }

    public void setLimitGroups(Map<String, Integer> limitGroups) {
      this.limitGroups = limitGroups;
    }

    public boolean isListeningToChat() {
      return listeningToChat;
    }

    public void setListeningToChat(boolean listeningToChat) {
      this.listeningToChat = listeningToChat;
    }

    public boolean isListeningToCommands() {
      return listeningToCommands;
    }

    public void setListeningToCommands(boolean listeningToCommands) {
      this.listeningToCommands = listeningToCommands;
    }
  }

  public static class CommandPipeEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private final Player player;
    private final String previousMessage;

    private boolean cancelled;
    private String currentMessage;

    public static HandlerList getHandlerList() {
      return handlers;
    }

    public CommandPipeEvent(Player player, String previousMessage, String currentMessage) {
      this.player = Preconditions.checkNotNull(player, "player cannot be null.");
      this.previousMessage = previousMessage;
      this.currentMessage = Preconditions.checkNotNull(currentMessage,
                                                       "current message cannot be null.");
    }

    @Override
    public HandlerList getHandlers() {
      return handlers;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
      this.cancelled = cancelled;
    }

    public Player getPlayer() {
      return player;
    }

    public String getPreviousMessage() {
      return previousMessage;
    }

    public String getCurrentMessage() {
      return currentMessage;
    }

    public void setCurrentMessage(String currentMessage) {
      Preconditions.checkNotNull(currentMessage, "message cannot be null.");
      this.currentMessage = currentMessage;
    }
  }

  private final class Replacer {

    private final Pattern pattern;
    private final String message;
    private final StringBuilder result;
    private boolean matches;
    private int currentIndex;

    public Replacer(Pattern pattern, String message) {
      this.pattern = pattern;
      this.message = message;
      this.result = new StringBuilder(message.length());
      Matcher matcher = this.pattern.matcher(message);
      while (matcher.find()) {
        matches = true;
        appendMessage(matcher.start(1));
        currentIndex = matcher.end(1);
      }

      if (currentIndex < message.length()) {
        appendMessage(message.length());
      }
    }

    @Override
    public String toString() {
      return this.result.toString();
    }

    public boolean matches() {
      return this.matches;
    }

    private void appendMessage(int index) {
      if (index <= currentIndex) {
        return;
      }

      String message = this.message.substring(currentIndex, index);
      this.currentIndex = index;
      result.append(message);
    }
  }
}
