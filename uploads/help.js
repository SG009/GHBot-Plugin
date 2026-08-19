// help.js — Dynamic help based on command metadata from command_handler.js
module.exports = (bot) => {
  bot.commands.register('help', (context) => {
    const { bot: b } = context;
    const commands = [...b.commands.entries()];

    b.chat(`[${b.username}] Available commands:`);

    if (commands.length === 0) {
      b.chat('  No commands registered.');
      return;
    }

    // Show detailed description and usage if available
    for (const [cmdName, cmdData] of commands) {
      const desc = cmdData.meta.description || 'No description available.';
      b.chat(`- ${b.username} ${cmdName}: ${desc}`);

      if (cmdData.meta.usage && Array.isArray(cmdData.meta.usage)) {
        cmdData.meta.usage.forEach((usageExample) => {
          b.chat(`    e.g. ${b.username} ${usageExample}`);
        });
      }
    }

    b.chat(`Type "${b.username} <command> [args]" to run a command.`);
  }, {
    description: 'Show available commands with descriptions and usage',
    usage: ['help']
  });
};
