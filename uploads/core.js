// core.js — Passive scan disabled on startup; enable from modules when needed
const ActivityStates = Object.freeze({
  IDLE: 'idle',
  FARMING: 'farming',
  SMELTING: 'smelting',
  EXPLORING: 'exploring'
});

module.exports = (bot) => {
  const os = require('os');
  const { Vec3 } = require('vec3');

  function getTime() {
    return new Date().toLocaleTimeString('en-GB', { hour12: false });
  }

  function logDebugInfo({ scannedBlocks, trackedEntities, radiusBlocks, radiusEntities, usedMem, totalMem, cpuPercent, uptime }) {
    if (!bot.memory.debugLogging) return;

    console.log(`[${getTime()}] [DEBUG] Memory blocks: ${scannedBlocks}`);
    console.log(`[${getTime()}] [DEBUG] Memory entities: ${trackedEntities}`);
    console.log(`[${getTime()}] [CORE] Block scan radius: ${radiusBlocks}`);
    console.log(`[${getTime()}] [CORE] Entity scan radius: ${radiusEntities}`);
    console.log(`[${getTime()}] [SYSTEM] CPU Usage: ${cpuPercent.toFixed(1)}%`);
    console.log(`[${getTime()}] [SYSTEM] Used RAM: ${usedMem.toFixed(1)}MB / ${totalMem.toFixed(1)}MB`);
    console.log(`[${getTime()}] [SYSTEM] Uptime: ${uptime.hours}h ${uptime.minutes}m ${uptime.seconds}s`);
  }

  // Configuration constants
  const MAX_BLOCKS = 10000;
  const MAX_ENTITIES = 500;
  const BLOCK_SCAN_INTERVAL_MS = 5000;
  const ENTITY_SCAN_INTERVAL_MS = 5000;
  const SYS_STATS_INTERVAL_MS = 5200;

  // Initialize bot memory
  bot.memory = bot.memory || {};
  bot.memory.scannedBlocks = [];
  bot.memory.trackedEntities = [];
  bot.memory.debugLogging = false; // Passive scan & debug log OFF by default!
  bot.memory.blocksRadius = 10;
  bot.memory.entitiesRadius = 10;
  bot.memory.activity = bot.memory.activity || ActivityStates.IDLE;

  // Activity helpers
  bot.isFarming = () => bot.memory.activity === ActivityStates.FARMING;
  bot.isSmelting = () => bot.memory.activity === ActivityStates.SMELTING;
  bot.isIdle = () => bot.memory.activity === ActivityStates.IDLE;
  bot.isExploring = () => bot.memory.activity === ActivityStates.EXPLORING;

  // Passive scan interval handles
  let blockScanInterval = null;
  let entityScanInterval = null;

  // Start passive scan timers; call this from modules to enable scanning & debug logging
  bot.startPassiveScan = function (opts = {}) {
    if (blockScanInterval || entityScanInterval) return;

    bot.memory.debugLogging = true;  // Enable debug logging when scanning starts
    bot.memory.blocksRadius = Number.isFinite(opts.blocksRadius) ? opts.blocksRadius : bot.memory.blocksRadius;
    bot.memory.entitiesRadius = Number.isFinite(opts.entitiesRadius) ? opts.entitiesRadius : bot.memory.entitiesRadius;

    blockScanInterval = setInterval(() => {
      if (!bot.entity?.position) return;
      const origin = bot.entity.position;
      const radius = bot.memory.blocksRadius;
      const scanned = [];

      for (let dx = -radius; dx <= radius; dx++) {
        for (let dy = -radius; dy <= radius; dy++) {
          for (let dz = -radius; dz <= radius; dz++) {
            const pos = origin.offset(dx, dy, dz).floored();
            const block = bot.blockAt(pos);
            if (block && block.name !== 'air') {
              scanned.push({ name: block.name, position: pos });
            }
          }
        }
      }
      bot.memory.scannedBlocks = scanned;

      if (scanned.length > MAX_BLOCKS) {
        console.log(`[${getTime()}] [CORE] Auto-clearing blocks: exceeded ${MAX_BLOCKS}`);
        bot.memory.scannedBlocks = [];
      }
    }, BLOCK_SCAN_INTERVAL_MS);

    entityScanInterval = setInterval(() => {
      if (!bot.entity?.position) return;
      const origin = bot.entity.position;
      const radius = bot.memory.entitiesRadius;

      const tracked = Object.values(bot.entities)
        .filter(e => e.username !== bot.username)
        .filter(e => e.position.distanceTo(origin) <= radius);

      bot.memory.trackedEntities = tracked;

      if (tracked.length > MAX_ENTITIES) {
        console.log(`[${getTime()}] [CORE] Auto-clearing entities: exceeded ${MAX_ENTITIES}`);
        bot.memory.trackedEntities = [];
      }

      // System stats for debug
      const totalMem = os.totalmem() / 1024 / 1024;
      const freeMem = os.freemem() / 1024 / 1024;
      const usedMem = totalMem - freeMem;

      const startCpu = process.cpuUsage();
      const startHr = process.hrtime();

      setTimeout(() => {
        const elapsedCpu = process.cpuUsage(startCpu);
        const elapsedHr = process.hrtime(startHr);

        const elapsedMs = elapsedHr[0] * 1000 + elapsedHr / 1e6 || 1; // avoid div 0
        const cpuUserMs = elapsedCpu.user / 1000;
        const cpuSysMs = elapsedCpu.system / 1000;

        let cpuPercent = ((cpuUserMs + cpuSysMs) / elapsedMs) * 100;
        if (!Number.isFinite(cpuPercent)) cpuPercent = 0;

        const uptimeSec = os.uptime();
        const hours = Math.floor(uptimeSec / 3600);
        const minutes = Math.floor((uptimeSec % 3600) / 60);
        const seconds = Math.floor(uptimeSec % 60);

        logDebugInfo({
          scannedBlocks: bot.memory.scannedBlocks.length,
          trackedEntities: bot.memory.trackedEntities.length,
          radiusBlocks: bot.memory.blocksRadius,
          radiusEntities: bot.memory.entitiesRadius,
          usedMem,
          totalMem,
          cpuPercent,
          uptime: { hours, minutes, seconds }
        });
      }, SYS_STATS_INTERVAL_MS);
    }, ENTITY_SCAN_INTERVAL_MS);

    console.log(`[${getTime()}] [Core] Passive scan started (blocks radius: ${bot.memory.blocksRadius}, entities radius: ${bot.memory.entitiesRadius})`);
  };

    // --- AFK / Head Behavior and Wander integrated with ActivityStates.IDLE ---   
    let isWalking = false;
    let wanderTimer = null;
    
    // Helper to log with timestamp (optional)
    function log(...args) {
      // Uncomment this to enable wandering logs:
      // console.log('[Wander]', ...args);
    }
    
    async function headBehaviorLoop() {
      if (!bot.entity?.position) return setTimeout(headBehaviorLoop, 10);
    
      // Look at nearby entities if any within 4 blocks
      const nearest = bot.nearestEntity(e => e?.position && e.position.distanceTo(bot.entity.position) <= 4);
    
      if (nearest) {
        stopWandering();
    
        const lookPos = nearest.position.offset(0, nearest.height ?? 1.5, 0);
        bot.lookAt(lookPos, true);
    
        // Repeat loop every 1.5 seconds while looking at entity
        setTimeout(headBehaviorLoop, 1500);
      } else {
        // Only start wandering if bot is idle
        if (!isWalking && bot.memory.activity === ActivityStates.IDLE) {
          startWandering();
        }
    
        // Occasionally look around randomly
        if (bot.memory.activity === ActivityStates.IDLE && Math.random() < 0.3) {
          const yaw = Math.random() * Math.PI * 2;
          const pitch = (Math.random() - 0.5) * 0.5;
          bot.look(yaw, pitch, true);
        }
    
        const nextDelay = 500 + Math.random() * 500;
        setTimeout(headBehaviorLoop, nextDelay);
      }
    }
    
    function startWandering() {
      if (isWalking) return;
      isWalking = true;
    
      async function walkCycle() {
        if (!isWalking || bot.isDead || bot.memory.activity !== ActivityStates.IDLE) {
          stopWandering();
          return;
        }
    
        bot.setControlState('forward', true);
    
        const walkDuration = 500 + Math.random() * 1000;
    
        setTimeout(() => {
          bot.setControlState('forward', false);
    
          const waitDuration = 5000 + Math.random() * 5000;
          wanderTimer = setTimeout(walkCycle, waitDuration);
        }, walkDuration);
      }
    
      walkCycle();
    }
    
    function stopWandering() {
      isWalking = false;
      bot.setControlState('forward', false);
      if (wanderTimer) clearTimeout(wanderTimer);
      wanderTimer = null;
    }
    
    // Hook into setActivity to start/stop wandering on activity change
    const originalSetActivity = bot.setActivity;
    bot.setActivity = (newActivity) => {
      if (bot.memory.activity !== newActivity) {
        originalSetActivity(newActivity);
        if (newActivity === ActivityStates.IDLE) {
          startWandering();
        } else {
          stopWandering();
        }
      }
    };
    
    // Start the head behavior loop immediately
    headBehaviorLoop();

  // Stop passive scan timers and disable debug logging
  bot.stopPassiveScan = function () {
    if (blockScanInterval) {
      clearInterval(blockScanInterval);
      blockScanInterval = null;
    }
    if (entityScanInterval) {
      clearInterval(entityScanInterval);
      entityScanInterval = null;
    }
    bot.memory.debugLogging = false;
    console.log(`[${getTime()}] [Core] Passive scan stopped`);
  };

  // Activity setter without auto scan start/stop; modules control passive scan explicitly
  bot.setActivity = (newActivity) => {
    if (bot.memory.activity !== newActivity) {
      console.log(`[Activity] ${bot.username} changed activity: ${bot.memory.activity} → ${newActivity}`);
      bot.memory.activity = newActivity;
      // NOTE: passive scan start/stop should be triggered by your modules explicitly as needed
    }
  };

  // Utility function
  function sanitizeName(name) {
    return (name || '').replace(/[^\w]/g, '').toLowerCase();
  }

  // Chat command example for memory clear
  if (bot.commands && typeof bot.commands.register === 'function') {
    bot.commands.register('memory', async ({ username, args }) => {
      const action = sanitizeName(args[0] || '');
      if (action === 'clear') {
        const prevBlocks = bot.memory.scannedBlocks.length;
        const prevEntities = bot.memory.trackedEntities.length;

        bot.memory.scannedBlocks = [];
        bot.memory.trackedEntities = [];

        console.log(`[${getTime()}] [CORE] Manual memory clear by ${username}: blocks ${prevBlocks}, entities ${prevEntities}`);
        bot.chat(`Memory cleared: ${prevBlocks} blocks, ${prevEntities} entities.`);
      }
    });
  }

  /*
  === HOW TO ENABLE PASSIVE SCAN & DEBUG LOGGING FROM MODULES ===
  
  In your other module files, when appropriate, call:
  
    bot.startPassiveScan({
      blocksRadius: 10,         // optional, defaults to 10
      entitiesRadius: 10        // optional, defaults to 10
    });
  
  To disable scanning and stop debug logging, call:
  
    bot.stopPassiveScan();

  Make sure to manage starting and stopping passive scan so it runs only when you want it.
  */
};
  
// Export ActivityStates for other modules
module.exports.ActivityStates = ActivityStates;
