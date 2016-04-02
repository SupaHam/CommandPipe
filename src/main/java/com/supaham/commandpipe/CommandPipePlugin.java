package com.supaham.commandpipe;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import com.supaham.commandpipe.CommandPipe.Configuration;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Pattern;

/**
 * Created by Ali on 05/04/2015.
 */
public class CommandPipePlugin extends JavaPlugin {

  public static CommandPipePlugin plugin;
  private CommandPipe commandPipe;

  public CommandPipePlugin() {
    Preconditions.checkArgument(plugin == null, "plugin already initialized.");
    plugin = this;
  }

  @Override
  public void onEnable() {
    super.onEnable();
    reload();
  }

  private void reload() {
    Configuration configuration = new Configuration();
    FileConfiguration config = getConfig();
    config.addDefault("escape-regex", configuration.getEscapeRegex().pattern());
    config.addDefault("regex", configuration.getRegex().pattern());
    config.addDefault("limit-groups", configuration.getLimitGroups());
    config.addDefault("listen-to-chat", configuration.isListeningToChat());
    config.addDefault("listen-to-commands", configuration.isListeningToCommands());
    config.options().copyDefaults(true);

    // necessary for bukkit to rewrite maps as sections
    saveConfig();
    reloadConfig();
    config = getConfig();

    configuration.setEscapeRegex(Pattern.compile(config.getString("escape-regex")));
    configuration.setRegex(Pattern.compile(config.getString("regex")));
    configuration.setLimitGroups(
        Maps.transformValues(config.getConfigurationSection("limit-groups").getValues(false),
                             ObjectToInteger.INSTANCE));
    configuration.setListeningToChat(config.getBoolean("listen-to-chat"));
    configuration.setListeningToCommands(config.getBoolean("listen-to-commands"));
    if (this.commandPipe != null) {
      this.commandPipe.destroy();
    }
    this.commandPipe = new CommandPipe(this, configuration, this.commandPipe);
  }

  @Override
  public void onDisable() {
    this.commandPipe.destroy();
    this.commandPipe = null;
  }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("cmdpipe.reload")) {
      sender.sendMessage(ChatColor.RED + "You don't have permission.");
      return true;
    }
    reload();
    sender.sendMessage(ChatColor.YELLOW + "You've successfully reloaded the CommandPipe configuration.");
    return true;
  }

  public static CommandPipePlugin getPlugin() {
    return plugin;
  }

  private static final class ObjectToInteger implements Function<Object, Integer> {

    public static final ObjectToInteger INSTANCE = new ObjectToInteger();

    @Override
    public Integer apply(Object input) {
      return ((Integer) input);
    }
  }
}
