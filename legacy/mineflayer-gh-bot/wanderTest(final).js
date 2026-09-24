const { Vec3 } = require('vec3')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const mcDataLoader = require('minecraft-data')

function wanderTest(bot) {
  // Ensure pathfinder is loaded first
  if (!bot.pathfinder) bot.loadPlugin(pathfinder)

  const visited = new Set()
  const cooldown = new Map()
  const scanRadius = 16
  const intervalMs = 25000 // Run every 25s

  let mcData
  bot.once('spawn', () => {
    mcData = mcDataLoader(bot.version)
    const defaultMove = new Movements(bot, mcData)
    bot.pathfinder.setMovements(defaultMove)
  })

  function blockKey(pos) {
    return `${pos.x},${pos.y},${pos.z}`
  }

  function randomOffsetVec(pos) {
    return new Vec3(
      pos.x + (Math.random() < 0.5 ? 1 : -1),
      pos.y,
      pos.z + (Math.random() < 0.5 ? 1 : -1)
    )
  }

  function getRarestBlock() {
    const blocks = {}
    const origin = bot.entity.position.floored()

    for (let dx = -scanRadius; dx <= scanRadius; dx++) {
      for (let dy = -scanRadius; dy <= scanRadius; dy++) {
        for (let dz = -scanRadius; dz <= scanRadius; dz++) {
          const pos = origin.offset(dx, dy, dz)
          const block = bot.blockAt(pos)
          if (!block || !block.name || block.name === 'air') continue

          const key = block.name
          if (!blocks[key]) blocks[key] = []
          blocks[key].push(block)
        }
      }
    }

    const sorted = Object.entries(blocks)
      .filter(([name, list]) => list.length > 0 && name !== 'air')
      .sort((a, b) => a[1].length - b[1].length)

    for (const [name, list] of sorted) {
      for (const block of list) {
        const key = blockKey(block.position)
        if (!visited.has(key)) {
          visited.add(key)
          return block
        }
      }
    }

    return null
  }

  function getNearestEntity() {
    let nearest = null
    let nearestDist = Infinity

    for (const id in bot.entities) {
      const entity = bot.entities[id]
      if (!entity.position || entity === bot) continue
      if (entity.type !== 'mob' && entity.type !== 'player') continue
      if (entity.username === bot.username) continue

      const dist = bot.entity.position.distanceTo(entity.position)
      const key = blockKey(entity.position)
      if (dist < nearestDist && !cooldown.has(key)) {
        nearest = entity
        nearestDist = dist
      }
    }

    if (nearest) {
      const key = blockKey(nearest.position)
      cooldown.set(key, Date.now())
    }

    return nearest
  }

  function wanderOnce() {
    if (!bot.pathfinder.movements) return

    const block = getRarestBlock()
    const entity = getNearestEntity()

    const rand = Math.random()
    let target = null
    let type = ''

    if (block && entity) {
      if (rand < 0.5) {
        target = block
        type = 'block'
      } else {
        target = entity
        type = 'entity'
      }
    } else if (block) {
      target = block
      type = 'block'
    } else if (entity) {
      target = entity
      type = 'entity'
    }

    if (!target) return

    const goalPos =
      type === 'block' ? randomOffsetVec(target.position) : randomOffsetVec(target.position)
    const goal = new goals.GoalBlock(goalPos.x, goalPos.y, goalPos.z)

    bot.pathfinder.setGoal(goal)
    if (type === 'block') {
      bot.chat(`Scanning ${target.name || target.displayName || 'something'}!`)
    } else {
      bot.chat(`Approaching ${target.name}...`)
    }

    bot.once('goal_reached', () => {
      if (type === 'entity') {
        bot.chat(`Hello ${target.name}!!`)
      }
      else if (type === 'block') {
        bot.chat(`Look, ${target.name}!`)
      }
    })
  }

  setInterval(wanderOnce, intervalMs)
}

module.exports = wanderTest