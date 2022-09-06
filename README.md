# Hostile Passives

Simple single class Spigot plugin that allows select passive mobs to become hostile towards players with configurable exempt users, damage, and movement speed values. Written for 1.19 but should work on most legacy versions as there's no use of nms

## How it works
Since passive entities cannot be made hostile even through nms, the plugin selectively summons an invisible silverfish (the 'controller' entity) at hostile passives nearby players and syncs the passive's location with the controller. We use a silverfish as the controller entity because of their small hitbox and generic hostile behavior. Additionally, the controllers movement speed is adjusted to match the passives. While the controller has priority, the passive's AI is disabled. When the passive takes damage the controller's AI is temporarily disabled and the passive's is temporarily re-enabled to allow for realistic knockback mechanics. When the passive dies the controller is removed with it. When players are outside of the check range (or the server shuts down), the controller entity is removed and the passive's AI is restored back to normal.

## Limitations
* Water & flying passives are not currently supported
* Some unique mob behavior may be limited or broken by the plugin while the entity is hostile, ex. frogs or rabbits hopping
* Since the plugin relies on hostile entities, the server cannot be on peaceful difficulty and have hostile passives
