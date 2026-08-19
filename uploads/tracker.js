// tracker.js — Refactored to use centralized ActivityStates from core.js
const { Vec3 } = require('vec3');
const core = require('./core');
const ActivityStates = core.ActivityStates;

module.exports = (bot) => {
  bot.trackedEntities = [];

  function updateTracking(radius = 100, origin = bot.entity?.position) {
    if (!origin) return;

    const entities = Object.values(bot.entities)
      .filter(e => e.username !== bot.username)
      .filter(e => e.position.distanceTo(origin) <= radius);

    bot.trackedEntities = entities;
  }

  function reportTracking(origin, radius, label, isSelf = false) {
    const nearby = bot.trackedEntities;
    const who = isSelf ? bot.username : label;

    if (!nearby.length) {
      bot.chat(`[Tracking] No entities near ${who} within ${radius} blocks.`);
      return;
    }

    bot.chat(`[Tracking] Found ${nearby.length} entities near ${who} (${radius} blocks):`);
    for (const e of nearby) {
      let type;
      if (e.type === 'player') type = `Player: ${e.username}`;
      else if (e.name === 'item') type = `Item: ${e.metadata?.[7]?.itemId ?? 'Unknown'}`;
      else type = `Mob: ${e.name}`;

      const pos = e.position.floored();
      bot.chat(`- ${type} at (${pos.x}, ${pos.y}, ${pos.z})`);
    }
  }

  // Chat command: GH001 tracking [me|playername|radius]
  bot.commands.register('tracking', async ({ username, args }) => {
    if (bot.memory.activity !== ActivityStates.IDLE) {
      bot.chat('Busy with other activities; unable to execute tracking command now.');
      return;
    }

    function sanitizeName(name) {
      return name.replace(/[^\w]/g, '').toLowerCase();
    }

    // Case: tracking [radius only]
    if (args.length === 1 && !isNaN(parseInt(args))) {
      const radius = parseInt(args);
      updateTracking(radius);
      reportTracking(bot.entity.position, radius, bot.username, true);
      return;
    }

    // Default case: tracking [me|playername] [radius]
    let rawTarget = args;
    let radius = parseInt(args) || 100;

    let targetName = rawTarget === 'me' ? username : rawTarget;
    const cleanTarget = sanitizeName(targetName);

    const matchedPlayerKey = Object.keys(bot.players).find(p => sanitizeName(p) === cleanTarget);
    const player = matchedPlayerKey ? bot.players[matchedPlayerKey] : null;

    if (!player || !player.entity) {
      bot.chat(`/data get entity ${targetName} Pos`);

      const listener = (msg) => {
        const text = msg.toString();
        if (!text.includes('has the following entity data')) return;

        const regex = /\[(-?\d+\.?\d*)d?, (-?\d+\.?\d*)d?, (-?\d+\.?\d*)d?\]/;
        const match = text.match(regex);
        if (match) {
          const pos = new Vec3(parseFloat(match), parseFloat(match), parseFloat(match));
          updateTracking(radius, pos);
          bot.chat(`[Remote] Tracking via server query:`);
          reportTracking(pos, radius, targetName);
          bot.removeListener('message', listener);
          clearTimeout(timeoutHandle);
        }
      };

      bot.on('message', listener);

      const timeoutHandle = setTimeout(() => {
        bot.removeListener('message', listener);
        bot.chat(`[Tracking] Couldn't find ${targetName}. Make sure the player is online.`);
      }, 3000);

      return;
    }

    const pos = player.entity.position;
    updateTracking(radius, pos);
    const isSelf = sanitizeName(bot.username) === cleanTarget;
    reportTracking(pos, radius, player.username, isSelf);
  });
};
