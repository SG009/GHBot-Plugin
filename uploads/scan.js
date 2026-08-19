// scan.js — Refactored to use centralized ActivityStates from core.js
const { Vec3 } = require('vec3');
const core = require('./core');
const ActivityStates = core.ActivityStates;

module.exports = (bot) => {
  bot.commands.register('find', async ({ username, args }) => {
    if (bot.memory.activity !== ActivityStates.IDLE) {
      bot.chat('Busy with other activities; unable to execute find command now.');
      return;
    }

    const botName = String(bot.username);
    if (args.length < 2) {
      bot.chat(`[${botName}] Usage: ${botName} find [me|playerName] block_name radius`);
      return;
    }

    const first = args[0].toLowerCase();
    const blockName = args.length === 2 ? first : args.toLowerCase();
    const radius = parseInt(args.length === 2 ? args : args) || 50;

    let targetName = args.length === 2 ? bot.username : (first === 'me' ? username : first);

    function sanitize(name) {
      return name.replace(/[^\w]/g, '').toLowerCase();
    }

    const cleanTarget = sanitize(targetName);
    const matchedPlayerKey = Object.keys(bot.players).find(p => sanitize(p) === cleanTarget);
    const player = matchedPlayerKey ? bot.players[matchedPlayerKey] : null;

    function scanBlocks(center, name, radius, label) {
      const found = [];
      const origin = center.floored();
      const min = origin.offset(-radius, -radius, -radius);
      const max = origin.offset(radius, radius, radius);

      outerLoop:
      for (let x = min.x; x <= max.x; x++) {
        for (let y = min.y; y <= max.y; y++) {
          for (let z = min.z; z <= max.z; z++) {
            const pos = new Vec3(x, y, z);
            const block = bot.blockAt(pos);
            if (block && block.name === name) {
              found.push(pos);
              if (found.length >= 10) break outerLoop;
            }
          }
        }
      }

      const lblName = String(label || bot.username);
      const bName = String(botName);

      if (found.length === 0) {
        bot.chat(`[${bName}] No ${name} found near ${lblName} (${radius} blocks).`);
        return;
      }

      bot.chat(`[${bName}] Found ${found.length} blocks of ${name} near ${lblName} (${radius} blocks):`);
      for (const pos of found) {
        bot.chat(`- ${name} at (${pos.x}, ${pos.y}, ${pos.z})`);
      }
    }

    if (player && player.entity) {
      scanBlocks(player.entity.position, blockName, radius, player.username);
    } else {
      // Fallback to EssentialsX /data get
      bot.chat(`/data get entity ${targetName} Pos`);

      const listener = (msg) => {
        const regex = new RegExp(`\\[.*\\] Entity data for ${targetName} has \\[(-?\\d+\\.?\\d*)d?, (-?\\d+\\.?\\d*)d?, (-?\\d+\\.?\\d*)d?\\]`);
        const match = msg.toString().match(regex);
        if (match) {
          const pos = new Vec3(parseFloat(match[1]), parseFloat(match), parseFloat(match));
          scanBlocks(pos, blockName, radius, targetName);
          bot.removeListener('message', listener);
          clearTimeout(timeout);
        }
      };

      bot.on('message', listener);

      const timeout = setTimeout(() => {
        bot.removeListener('message', listener);
        bot.chat(`[${botName}] Could not find ${targetName}. Ensure they are online and EssentialsX is working.`);
      }, 3000);
    }
  });
};
