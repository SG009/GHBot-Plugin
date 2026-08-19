// command_handler.js — Refactored for async safety and dynamic help
module.exports = (bot) => {
  bot.commands = new Map();

  // Sanitize command names (case-insensitive)
  const sanitizeName = (name) => (name || '').trim().toLowerCase();

  // Register commands with optional metadata
  bot.commands.register = (name, fn, meta = {}) => {
    bot.commands.set(sanitizeName(name), { fn, meta });
  };

  // Execute commands async-safe with error handling
  bot.commands.execute = async (name, context) => {
    const cmd = bot.commands.get(sanitizeName(name));
    if (cmd) {
      try {
        await Promise.resolve(cmd.fn(context));
      } catch (err) {
        console.error(`[Commands] Error executing "${name}":`, err);
        bot.chat(`§cError executing command "${name}". Check console for details.`);
      }
    } else {
      bot.chat(`§cUnknown command: "${name}"`);
      bot.chat(`§eType "${bot.username} help" for available commands.`);
    }
  };

  // Register built-in help command dynamically listing all commands
  bot.commands.register('help', (context) => {
    const { bot: b } = context;
    const cmds = [...b.commands.keys()];
    b.chat(`[${b.username}] Available commands:`);
    if (cmds.length === 0) {
      b.chat('  No commands registered.');
      return;
    }
    cmds.forEach((cmdName) => {
      b.chat(`  - ${cmdName}`);
    });
    b.chat(`Type "${b.username} <command> [args]" to run a command.`);
  }, {
    description: 'Show this help message'
  });
};
