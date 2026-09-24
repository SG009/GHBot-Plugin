// farm.js — Refactored to use shared ActivityStates from core.js
const core = require('./core');
const ActivityStates = core.ActivityStates;
const { goals: { GoalNear } } = require('mineflayer-pathfinder');

module.exports = (bot) => {
  let farmInterval = null;
  let farmRadius = 8; // default radius
  let harvestedCount = 0;
  let skippedCount = 0;
  let storedCount = 0;

  function isMatureCrop(block) {
    if (!block) return false;
    if (block.name === 'wheat') return block.metadata === 7;
    if (block.name === 'carrots' || block.name === 'potatoes') return block.metadata === 7;
    if (block.name === 'beetroots') return block.metadata === 3;
    return false;
  }

  async function moveToBlock(block, range = 1) {
    return new Promise((resolve, reject) => {
      const goal = new GoalNear(block.position.x, block.position.y, block.position.z, range);
      bot.pathfinder.setGoal(goal);

      const timeout = setTimeout(() => {
        bot.pathfinder.setGoal(null);
        reject(new Error('Timeout while moving to block'));
      }, 5000);

      bot.once('goal_reached', () => {
        clearTimeout(timeout);
        resolve();
      });

      bot.once('path_update', (r) => {
        if (r.status === 'noPath') {
          clearTimeout(timeout);
          reject(new Error('No path to block'));
        }
      });
    });
  }

  async function harvestAndReplant(block) {
    try {
      await moveToBlock(block);
      await bot.dig(block);

      await new Promise(res => setTimeout(res, 5000)); // Wait for block update
      bot.chat('/heal');

      const seedSlot = bot.inventory.items().find(item =>
        item.name.includes('seeds') ||
        item.name === 'carrot' ||
        item.name === 'potato' ||
        item.name === 'beetroot_seeds'
      );
      if (seedSlot) {
        await bot.equip(seedSlot, 'hand');
        const soil = bot.blockAt(block.position.offset(0, -1, 0));
        if (soil) {
          await bot.placeBlock(soil, { x: 0, y: 1, z: 0 });
        }
      }

      harvestedCount++;
    } catch (err) {
      skippedCount++;
      console.log(`[Farm] Failed to harvest: ${err.message}`);
    }
  }

  async function pickupDrops() {
    const drops = Object.values(bot.entities).filter(e => e.name === 'item');
    for (const drop of drops) {
      try {
        const pos = drop.position.floored();
        await moveToBlock({ position: pos }, 2);
      } catch (err) {
        console.log(`[Farm] Failed to pickup drop: ${err.message}`);
      }
    }
  }

  async function storeHarvest() {
    const chest = bot.findBlock({
      matching: block => block.name.includes('chest'),
      maxDistance: 6
    });
    if (!chest) return;

    try {
      await moveToBlock(chest, 2);
      const chestWindow = await bot.openChest(chest);

      for (const item of bot.inventory.items()) {
        if (item.name.includes('wheat') ||
            item.name.includes('carrot') ||
            item.name.includes('potato') ||
            item.name.includes('beetroot')) {
          await chestWindow.deposit(item.type, null, item.count);
          storedCount += item.count;
        }
      }

      chestWindow.close();
    } catch (err) {
      console.log(`[Farm] Failed to store items: ${err.message}`);
    }
  }

  async function farmingLoop() {
    if (bot.memory.activity !== ActivityStates.FARMING) return;

    const center = bot.entity.position;
    const blocks = bot.findBlocks({
      matching: isMatureCrop,
      maxDistance: farmRadius,
      count: 50
    });

    if (blocks.length === 0) {
      console.log('[Farm] No mature crops found this cycle.');
    }

    for (const pos of blocks) {
      if (bot.memory.activity !== ActivityStates.FARMING) break;
      const block = bot.blockAt(pos);
      if (block && isMatureCrop(block)) {
        await harvestAndReplant(block);
      }
    }

    await pickupDrops();
    await storeHarvest();

    console.log(`[Farm] Cycle summary -> Harvested: ${harvestedCount}, Skipped: ${skippedCount}, Stored: ${storedCount}`);
    bot.chat('/heal');

    if (bot.memory.activity === ActivityStates.FARMING) {
      farmInterval = setTimeout(farmingLoop, 20000);
    }
  }

  function sanitizeName(name) {
    return (name || '').replace(/[^\w]/g, '').toLowerCase();
  }

  if (bot.commands && typeof bot.commands.register === 'function') {
    bot.commands.register('farm', async ({ args }) => {
      const action = sanitizeName(args[0] || '');
      if (action === 'start' && bot.memory.activity !== ActivityStates.FARMING) {
        bot.setActivity(ActivityStates.FARMING);
        harvestedCount = 0;
        skippedCount = 0;
        storedCount = 0;
        const r = parseInt(args[1], 10);
        if (!isNaN(r)) {
          farmRadius = Math.max(2, Math.min(8, r));
        }
        bot.chat(`[Farm] Farming started with radius ${farmRadius}`);
        farmingLoop();
      } else if (action === 'stop' && bot.memory.activity === ActivityStates.FARMING) {
        bot.setActivity(ActivityStates.IDLE);
        if (farmInterval) clearTimeout(farmInterval);
        farmInterval = null;
        bot.pathfinder.setGoal(null);
        bot.chat('[Farm] Farming stopped');
        bot.chat(`[Farm] Cycle summary -> Harvested: ${harvestedCount}, Skipped: ${skippedCount}, Stored: ${storedCount}`);
      }
    });
  }
};
