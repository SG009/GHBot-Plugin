//smelt.js

const { pathfinder, Movements, goals: { GoalNear } } = require('mineflayer-pathfinder');
const core = require('./core');
const ActivityStates = core.ActivityStates;
const Vec3 = require('vec3');
let goToBlockNear;

module.exports = (bot, options = {}) => {
  if (!bot.pathfinder) {
    bot.loadPlugin(pathfinder);
  }

  const Item = require('prismarine-item')(bot.registry);
  let mcData;

  let depositAllSmelted = options.depositAllSmelted ?? true;
  let depositJunk = options.depositJunk ?? false;
  let smeltRadius = options.smeltRadius ?? 8;
  const moveTimeout = options.moveTimeout ?? 7000;

  let smeltInterval = null;

  const cookableRawNames = new Set([
    'porkchop', 'beef', 'chicken', 'mutton',
    'rabbit', 'salmon', 'cod', 'potato', 'raw_iron'
  ]);
  const wheatName = 'wheat';
  const breadName = 'bread';
  const fuelNames = new Set(['coal', 'charcoal', 'stick', 'lava_bucket', 'blaze_powder']);
  const junkNames = new Set(['dirt', 'cobblestone', 'gravel', 'sand', 'stick', 'string']);

  bot.memory.smelt = bot.memory.smelt || {};
  const memory = bot.memory.smelt;

  memory.furnaces = memory.furnaces || [];
  memory.chests = memory.chests || [];

  function log(...args) {
    console.log('[Smelt]', ...args);
  }

  const sleep = ms => new Promise(res => setTimeout(res, ms));

  function findItemsByNames(namesSet) {
    return bot.inventory.items().filter(i => i && namesSet.has(i.name));
  }

  function canFitItem(item) {
    if (!item) return false;
    const maxStack = bot.registry.itemsByName[item.name]?.stackSize || 32;

    const stackable = bot.inventory.items().filter(i => i.name === item.name && i.count < maxStack);
    if (stackable.length > 0) return true;

    return bot.inventory.slots.some(slot => slot == null);
  }

  function fuelItem() {
    const fuels = findItemsByNames(fuelNames);
    return fuels.length > 0 ? fuels[0] : null;
  }

  function rawFoodItem() {
    return bot.inventory.items().find(i => i && cookableRawNames.has(i.name));
  }

  function wheatCount() {
    return bot.inventory.count(mcData.itemsByName[wheatName].id);
  }

  async function rescanWorld() {
    log('Scanning nearby furnaces and chests...');
    const blocks = bot.findBlocks({
      matching: b =>
        b.name === 'furnace' || b.name === 'blast_furnace' || b.name === 'chest',
      maxDistance: smeltRadius,
      count: 50,
      includeMetadatas: true,
    });

    memory.furnaces = [];
    memory.chests = [];

    blocks.forEach(pos => {
      const block = bot.blockAt(pos);
      if (!block) return;
      if (block.name === 'furnace' || block.name === 'blast_furnace') {
        memory.furnaces.push(pos.floored());
      }
      if (block.name === 'chest') {
        memory.chests.push(pos.floored());
      }
    });

    log(`Found ${memory.furnaces.length} furnaces and ${memory.chests.length} chests.`);
  }

  async function validateMemoryPositions() {
    let changed = false;
    memory.furnaces = memory.furnaces.filter(pos => {
      const block = bot.blockAt(pos);
      const valid = block && (block.name === 'furnace' || block.name === 'blast_furnace');
      if (!valid) {
        changed = true;
        log(`Furnace missing or moved at ${pos.toString()}`);
      }
      return valid;
    });

    memory.chests = memory.chests.filter(pos => {
      const block = bot.blockAt(pos);
      const valid = block && (block.name === 'chest');
      if (!valid) {
        changed = true;
        log(`Chest missing or moved at ${pos.toString()}`);
      }
      return valid;
    });

    if (changed) {
      log('Rescanning due to missing blocks...');
      await rescanWorld();
      if (memory.furnaces.length === 0 || memory.chests.length === 0) {
        log('No valid furnaces or chests after rescan, pausing...');
        bot.setActivity(ActivityStates.IDLE);
        stopSmeltCycle();
      }
    }
  }

  async function withdrawFromChest(chestPos) {
    if (!chestPos) return false;

    const chestBlock = bot.blockAt(chestPos);
    if (!chestBlock) {
      log(`Chest missing at ${chestPos.toString()}`);
      return false;
    }

    try {
      const chest = await bot.openChest(chestBlock);

      for (const item of chest.containerItems()) {
        if (!(cookableRawNames.has(item.name) || fuelNames.has(item.name))) continue;

        if (bot.inventory.count(item.name) >= 64) {
          log(`Enough ${item.name} in inventory (${bot.inventory.count(item.name)}), stopping withdrawal.`);
          break;
        }

        if (!canFitItem(item)) {
          log(`Insufficient space for ${item.name}, skipping withdrawal.`);
          continue;
        }

        const amountToWithdraw = Math.min(item.count, 64);

        try {
          await chest.withdraw(item.type, null, amountToWithdraw);
          log(`Withdrew ${amountToWithdraw}x ${item.name}`);
          await sleep(300);
        } catch (e) {
          log(`Failed to withdraw ${item.name}: ${e.message}`);
        }
      }

      await chest.close();
      return true;
    } catch (e) {
      log(`Error withdrawing from chest: ${e.message}`);
      return false;
    }
  }

  async function depositToChest(chestPos) {
    if (!chestPos) return false;

    const chestBlock = bot.blockAt(chestPos);
    if (!chestBlock) {
      log(`Chest missing at ${chestPos.toString()}`);
      return false;
    }

    try {
      const chest = await bot.openChest(chestBlock);

      const cookedNames = new Set([
        'cooked_porkchop', 'cooked_beef', 'cooked_chicken', 'cooked_mutton',
        'cooked_rabbit', 'cooked_salmon', 'cooked_cod', 'baked_potato',
      ]);
      let toDeposit = bot.inventory.items().filter(i => i && cookedNames.has(i.name));
      toDeposit.push(...bot.inventory.items().filter(i => i && i.name === breadName));
      if (depositAllSmelted) {
        const smeltedNames = new Set(['iron_ingot', 'gold_ingot', 'stone', 'glass', ...cookedNames]);
        toDeposit = toDeposit.concat(bot.inventory.items().filter(i => i && smeltedNames.has(i.name)));
      }
      if (depositJunk) {
        toDeposit = toDeposit.concat(bot.inventory.items().filter(i => i && junkNames.has(i.name)));
      }

      for (const item of toDeposit) {
        try {
          await chest.deposit(item.type, null, item.count);
          log(`Deposited ${item.count}x ${item.name}`);
          bot.chat('/heal');
          await sleep(300);
        } catch (e) {
          log(`Failed to deposit ${item.name}: ${e.message}`);
        }
      }

      await chest.close();
      return true;
    } catch (e) {
      log(`Error depositing to chest: ${e.message}`);
      return false;
    }
  }

  async function craftBread() {
    const wheat = bot.inventory.items().find(i => i && i.name === wheatName);
    if (!wheat || wheat.count < 3) return false;

    const breadRecipe = bot.recipesAll(mcData.itemsByName[breadName].id, null, 1)[0];
    if (!breadRecipe) {
      log('No bread recipe found.');
      return false;
    }

    try {
      const craftingTables = bot.findBlocks({
        matching: b => b.name === 'crafting_table',
        maxDistance: 5,
        count: 1
      });

      if (craftingTables.length === 0) {
        log('No crafting table nearby; cannot craft bread.');
        return false;
      }

      const craftingTableBlock = bot.blockAt(craftingTables[0]);

      log('Crafting bread using crafting table...');
      await bot.craft(breadRecipe, 1, craftingTableBlock);
      log('Bread crafted.');
      bot.chat('/heal');
      return true;
    } catch (e) {
      log(`Failed to craft bread: ${e.message}`);
      return false;
    }
  }

  async function smeltOnFurnace(furnacePos) {
    const block = bot.blockAt(furnacePos);
    if (!block || (block.name !== 'furnace' && block.name !== 'blast_furnace')) {
      log(`Furnace invalid at ${furnacePos.toString()}`);
      return false;
    }
    let furnace;
    try {
      furnace = await bot.openFurnace(block);
    } catch (e) {
      log(`Failed to open furnace: ${e.message}`);
      return false;
    }

    try {
      const fuel = furnace.fuelItem();
      if (!fuel || fuel.count < 5) {
        const fi = fuelItem();
        if (fi) {
          await furnace.putFuel(fi.type, null, fi.count);
          log(`Fueled furnace at ${furnacePos.toString()} with ${fi.count}x ${fi.name}`);
          await sleep(300);
        }
      }
    } catch (e) {
      log(`Failed to put fuel: ${e.message}`);
    }

    try {
      const inputItem = furnace.inputItem();
      const raw = rawFoodItem();

      if (raw) {
        if (!inputItem || (inputItem.type === raw.type && inputItem.count < 64)) {
          await furnace.putInput(raw.type, null, raw.count);
          log(`Loaded furnace at ${furnacePos.toString()} with ${raw.count}x ${raw.name}`);
          await sleep(300);
        } else {
          log(` Furnace input slot blocked or full, skipping input.`);
        }
      }
    } catch (e) {
      if (e.message.includes('destination full')) {
        log(` Furnace input full, skipping this furnace.`);
      } else {
        log(`Failed to put input: ${e.message}`);
      }
    }

    try {
      const output = furnace.outputItem();
      if (output) {
        await furnace.takeOutput();
        log(`Collected ${output.count}x ${output.name} from furnace`);
        await sleep(300);
      }
    } catch (e) {
      log(`Failed to take output: ${e.message}`);
    }

    furnace.close();
    return true;
  }

  async function smeltingLoop() {
    if (bot.memory.activity !== ActivityStates.SMELTING) return;

    await validateMemoryPositions();
    if (memory.furnaces.length === 0 || memory.chests.length === 0) {
      log('No furnaces or chests; stopping smelt cycle.');
      bot.setActivity(ActivityStates.IDLE);
      return;
    }

    for (const chestPos of memory.chests) {
      if (bot.memory.activity !== ActivityStates.SMELTING) break;
      await goToBlockNear(chestPos);
      if (await withdrawFromChest(chestPos)) break;
    }

    for (const furnacePos of memory.furnaces) {
      if (bot.memory.activity !== ActivityStates.SMELTING) break;
      await goToBlockNear(furnacePos);
      await smeltOnFurnace(furnacePos);

      if (wheatCount() >= 3) await craftBread();

      await sleep(1500);
    }

    if (bot.memory.activity !== ActivityStates.SMELTING) return;

    for (const chestPos of memory.chests) {
      if (bot.memory.activity !== ActivityStates.SMELTING) break;
      await goToBlockNear(chestPos);
      if (await depositToChest(chestPos)) break;
    }

    if (bot.memory.activity === ActivityStates.SMELTING) {
      smeltInterval = setTimeout(smeltingLoop, 1500);
    }
  }

  function startSmelt(radius = smeltRadius, depositAll = depositAllSmelted) {
    if (bot.memory.activity === ActivityStates.SMELTING) {
      bot.chat(' Smelt cycle already running.');
      return;
    }
    smeltRadius = radius;
    depositAllSmelted = depositAll;
    bot.setActivity(ActivityStates.SMELTING);
    rescanWorld().then(() => smeltingLoop());
  }

  function stopSmeltCycle() {
    if (bot.memory.activity !== ActivityStates.SMELTING) return;
    bot.setActivity(ActivityStates.IDLE);
    if (smeltInterval) {
      clearTimeout(smeltInterval);
      smeltInterval = null;
    }
    bot.chat(' Smelt cycle stopped.');
  }

  bot.once('spawn', () => {
    mcData = require('minecraft-data')(bot.version);
    const defaultMove = new Movements(bot, mcData);
    bot.pathfinder.setMovements(defaultMove);

    goToBlockNear = async function(pos) {
      try {
        await bot.pathfinder.goto(new GoalNear(pos.x, pos.y, pos.z, 1));
        return true;
      } catch (err) {
        log('Pathfinder error:', err.message);
        return false;
      }
    };

    if (bot.commands && typeof bot.commands.register === 'function') {
      bot.commands.register('smelt', async ({ args }) => {
        const action = (args[0] || '').toLowerCase();

        if (action === 'start') {
          const radiusArg = parseInt(args[1]);
          const radius = !isNaN(radiusArg) ? radiusArg : smeltRadius;
          const depositFlag = (args[2] || 'food').toLowerCase();
          const depositAll = depositFlag === 'all';

          startSmelt(radius, depositAll);
          bot.chat(` Started with radius ${radius}, mode: ${depositFlag}`);
        } else if (action === 'stop') {
          stopSmeltCycle();
        } else if (action === 'rescan') {
          await rescanWorld();
          bot.chat(' Rescanned furnaces and chests.');
        } else {
          bot.chat(' Invalid command. Usage: start [radius] [food|all], stop, rescan.');
        }
      });
    }
  });

  bot.once('end', () => {
    stopSmeltCycle();
    memory.furnaces = [];
    memory.chests = [];
  });
};
