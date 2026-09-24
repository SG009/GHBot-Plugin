// index.js #attempt005 - FIXED VERSION
const mineflayer = require('mineflayer');
const Movements = require('mineflayer-pathfinder').Movements;
const pathfinder = require('mineflayer-pathfinder').pathfinder;
const { GoalBlock } = require('mineflayer-pathfinder').goals;

const config = require('./settings.json');
const theBrain = require('./plugins/core');
const trackerPlugin = require('./plugins/tracker');
const scanPlugin = require('./plugins/scan');
const helpPlugin = require('./plugins/help');
const farmPlugin = require('./plugins/farm');
const smeltPlugin = require('./plugins/smelt');

const commandHandler = require('./plugins/command_handler');
const express = require('express');
const readline = require('readline');

const app = express();
let viewerInitialized = false;

app.get('/', (req, res) => {
  res.send('Bot is alive');
});

app.listen(8000, () => {
  console.log('server started');
});

const activeBots = {};

function createBot(account) {
  const bot = mineflayer.createBot({
    username: account.username,
    password: account.password,
    auth: account.type,
    host: config.server.ip,
    port: config.server.port,
    version: config.server.version,
  });

  // CRITICAL: Load pathfinder FIRST
  bot.loadPlugin(pathfinder);

  // Load plugins AFTER pathfinder
  commandHandler(bot);
  helpPlugin(bot);
  theBrain(bot);
  trackerPlugin(bot, config);
  scanPlugin(bot, config);
  farmPlugin(bot);
  smeltPlugin(bot);

  // Store bot reference immediately
  activeBots[account.id] = bot;
  bot.autoReconnect = config.utils['auto-reconnect'];

  // SPAWN EVENT - All initialization here
  bot.once('spawn', () => {
    console.log(`\x1b[33m${account.id} joined the server\x1b[0m`);

    // Setup pathfinder movements AFTER spawn
    const mcData = require('minecraft-data')(bot.version);
    const defaultMove = new Movements(bot, mcData);
    bot.pathfinder.setMovements(defaultMove);

    // Auto-auth
    if (config.utils['auto-auth'].enabled) {
      console.log('[INFO] Started auto-auth module');
      const password = config.utils['auto-auth'].password;
      setTimeout(() => {
        bot.chat(`/register ${password} ${password}`);
        bot.chat(`/login ${password}`);
      }, 500);
    }

    // Chat messages
    if (config.utils['chat-messages'].enabled) {
      console.log('[INFO] Started chat-messages module');
      const messages = config.utils['chat-messages']['messages'];

      if (config.utils['chat-messages'].repeat) {
        const delay = config.utils['chat-messages']['repeat-delay'];
        let i = 0;
        const chatInterval = setInterval(() => {
          if (!activeBots[account.id]) {
            clearInterval(chatInterval);
            return;
          }
          bot.chat(`${messages[i]}`);
          i = (i + 1) % messages.length;
        }, delay * 1000);
      } else {
        messages.forEach((msg) => setTimeout(() => bot.chat(msg), 1000));
      }
    }

    // Move to position
    if (config.position.enabled) {
      const pos = config.position;
      console.log(
        `\x1b[32m[${account.id}] Moving to target location (${pos.x}, ${pos.y}, ${pos.z})\x1b[0m`
      );
      bot.pathfinder.setGoal(new GoalBlock(pos.x, pos.y, pos.z));
    }
  });

  // CHAT COMMAND HANDLER - FIXED scope issue
  bot.on('chat', (username, message) => {
    if (username === bot.username) return;

    const parts = message.trim().split(/\s+/);
    const commandTarget = parts[0];
    const commandName = parts[1];
    const args = parts.slice(2);

    if (commandTarget !== bot.username) return;

    if (bot.commands && bot.commands.execute) {
      bot.commands.execute(commandName, {
        bot,
        username,
        args,
      });
    } else {
      bot.chat("§cCommand system not loaded.");
    }
  });

  // MESSAGE LOGGER
  bot.on('message', (jsonMsg) => {
    const msg = jsonMsg.toString().trim();

    if (config.utils['chat-log']) {
      const now = new Date();
      const wib = new Date(now.getTime() + 7 * 60 * 60 * 1000);
      const pad = (n) => n.toString().padStart(2, '0');
      const formatted = `${wib.getUTCFullYear()}-${pad(wib.getUTCMonth() + 1)}-${pad(wib.getUTCDate())} ${pad(wib.getUTCHours())}:${pad(wib.getUTCMinutes())}:${pad(wib.getUTCSeconds())}`;
      console.log(`[${formatted}] ${msg}`);
    }

    if (msg.includes(bot.username)) return;
  });

  // PATHFINDER GOAL REACHED
  bot.on('goal_reached', () => {
    console.log(`\x1b[32m[${account.id}] Arrived at target location. ${bot.entity.position}\x1b[0m`);
  });

  // DEATH HANDLER
  bot.on('death', () => {
    console.log(`\x1b[33m[${account.id}] Died and respawned at ${bot.entity.position}\x1b[0m`);
  });

  // FIXED END HANDLER - Proper cleanup and reconnect
  bot.on('end', () => {
    console.log(`[${account.id}] Disconnected.`);
    delete activeBots[account.id]; // Clean up old reference FIRST
    
    if (account.autoReconnect && config.utils['auto-reconnect']) {
      console.log(`[${account.id}] Reconnecting in ${config.utils['auto-reconnect-delay']}ms...`);
      setTimeout(() => {
        const newAccount = config['bot-accounts'].find(acc => acc.id === account.id);
        if (newAccount) {
          activeBots[account.id] = createBot(newAccount);
        }
      }, config.utils['auto-reconnect-delay']);
    }
  });

  bot.on('kicked', (reason) =>
    console.log(`\x1b[33m[${account.id}] Kicked: ${reason}\x1b[0m`)
  );

  bot.on('error', (err) =>
    console.log(`\x1b[31m[${account.id}] [ERROR] ${err.message}\x1b[0m`)
  );

  return bot;
}

// Console commands - FIXED parsing
const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
});

console.log("Type 'start GH001' or 'stop GH002' to control bots.");
console.log("Other commands: pScan <BotName> <on|off>, debuglog <BotName> <show|hide>");

rl.on('line', (input) => {
  const parts = input.trim().split(' ');
  const command = parts[0];
  const botName = parts[1];
  const subcommand = parts[2];

  if (command === 'start') {
    const account = config['bot-accounts'].find(acc => acc.id === botName);
    if (!account) return console.log(`Bot Name ${botName} not found in settings.json`);
    if (activeBots[botName]) return console.log(`[${botName}] Already running.`);

    activeBots[botName] = createBot(account);
    console.log(`[${botName}] Started with auto-reconnect enabled.`);
  } else if (command === 'stop') {
    if (!activeBots[botName]) return console.log(`[${botName}] Not running.`);

    activeBots[botName].autoReconnect = false;
    activeBots[botName].quit();
    delete activeBots[botName];
    console.log(`[${botName}] Stopped.`);
  } else if (command === 'pScan') {
    if (!botName || !subcommand || !['on', 'off'].includes(subcommand)) {
      console.log("Usage: pScan <BotName> <on|off>");
      return;
    }
    const bot = activeBots[botName];
    if (!bot) {
      console.log(`[${botName}] Bot is not running.`);
      return;
    }
    if (subcommand === 'on') {
      if (bot.startPassiveScan) bot.startPassiveScan();
      console.log(`[${botName}] Passive scan enabled.`);
    } else {
      if (bot.stopPassiveScan) bot.stopPassiveScan();
      console.log(`[${botName}] Passive scan disabled.`);
    }
  } else if (command === 'debuglog') {
    if (!botName || !subcommand || !['show', 'hide'].includes(subcommand)) {
      console.log("Usage: debuglog <BotName> <show|hide>");
      return;
    }
    const bot = activeBots[botName];
    if (!bot || !bot.memory) {
      console.log(`[${botName}] Bot or memory not available.`);
      return;
    }
    if (subcommand === 'show') {
      bot.memory.debugLogging = true;
      console.log(`[${botName}] Debug logging enabled.`);
    } else {
      bot.memory.debugLogging = false;
      console.log(`[${botName}] Debug logging disabled.`);
    }
  } else {
    console.log(`AVAILABLE COMMANDS:`);
    console.log("  start|stop <BotName>");
    console.log("  pScan <BotName> <on|off>");
    console.log("  debuglog <BotName> <show|hide>");
  }
});

// Auto-start first bot with proper account reference
if (config['bot-accounts'] && config['bot-accounts'].length > 0) {
  const defaultBot = config['bot-accounts'][0];
  activeBots[defaultBot.id] = createBot(defaultBot);
  console.log(`Auto-started ${defaultBot.id}`);
}
