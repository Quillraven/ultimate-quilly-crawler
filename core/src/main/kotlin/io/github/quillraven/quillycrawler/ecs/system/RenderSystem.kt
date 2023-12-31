package io.github.quillraven.quillycrawler.ecs.system

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.utils.viewport.Viewport
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import com.github.quillraven.fleks.collection.compareEntityBy
import io.github.quillraven.quillycrawler.QuillyCrawler.Companion.UNIT_SCALE
import io.github.quillraven.quillycrawler.assets.Assets
import io.github.quillraven.quillycrawler.assets.ShaderAssets
import io.github.quillraven.quillycrawler.ecs.component.Boundary
import io.github.quillraven.quillycrawler.ecs.component.Dissolve
import io.github.quillraven.quillycrawler.ecs.component.Graphic
import io.github.quillraven.quillycrawler.event.*
import io.github.quillraven.quillycrawler.map.ConnectionType
import ktx.assets.disposeSafely
import ktx.graphics.update
import ktx.graphics.use
import ktx.math.vec2
import ktx.tiled.totalHeight
import ktx.tiled.totalWidth
import ktx.tiled.x
import ktx.tiled.y
import kotlin.math.min


class RenderSystem(
    private val batch: Batch = inject(),
    private val viewport: Viewport = inject(),
    assets: Assets = inject()
) : IteratingSystem(
    family = family { all(Graphic, Boundary) },
    comparator = compareEntityBy(Boundary),
), EventListener {

    private val orthoCamera = viewport.camera as OrthographicCamera
    private val mapRenderer = OrthogonalTiledMapRenderer(null, UNIT_SCALE, batch)

    private var doTransition = false
    private var transitionInterpolation = Interpolation.linear
    private var transitionAlpha = 0f
    private val transitionMapRenderer = OrthogonalTiledMapRenderer(null, UNIT_SCALE, batch)
    private val transitionFrom = vec2()
    private val transitionTo = vec2()
    private val transitionOffset = vec2()

    private val dissolveEntities = mutableListOf<Entity>()
    private val dissolveShader = assets[ShaderAssets.DISSOLVE]

    override fun onTick() {
        viewport.apply()

        if (doTransition) {
            transitionAlpha = (transitionAlpha + deltaTime * TRANSITION_SPEED).coerceAtMost(1f)

            orthoCamera.position.x = transitionInterpolation.apply(transitionFrom.x, transitionTo.x, transitionAlpha)
            orthoCamera.position.y = transitionInterpolation.apply(transitionFrom.y, transitionTo.y, transitionAlpha)

            if (transitionMapRenderer.map != null) {
                orthoCamera.update {
                    position.x -= transitionOffset.x
                    position.y += transitionOffset.y
                }

                transitionMapRenderer.setView(orthoCamera)
                transitionMapRenderer.render()
            }

            orthoCamera.update {
                position.x += transitionOffset.x
                position.y -= transitionOffset.y
            }

            mapRenderer.setView(orthoCamera)
            val origAlpha = batch.color.a
            batch.color.a = 1f - transitionAlpha
            mapRenderer.render()
            batch.color.a = origAlpha

            doTransition = transitionAlpha < 1f
            if (!doTransition) {
                // transition ended -> change to new active map for rendering
                mapRenderer.map = transitionMapRenderer.map
                transitionMapRenderer.map = null
                // MapTransitionStopEvent is relocating the player's position relative to the new map's boundaries.
                // Therefore, we need to render the entities before triggering the event to avoid some flickering.
                // In the next frame, entity positions are aligned again to the new map and can follow the normal
                // render logic.
                renderEntities()
                EventDispatcher.dispatch(MapTransitionStopEvent)
                return
            }
        } else if (mapRenderer.map != null) {
            mapRenderer.setView(orthoCamera)
            mapRenderer.render()
        }

        renderEntities()
    }

    private fun renderEntities() {
        batch.use(orthoCamera) {
            dissolveEntities.clear()
            // render all entities (+ adjust their sprite position, size, ...)
            super.onTick()
            // render any dissolve entities. This requires a shader change (=flush)
            if (dissolveEntities.isNotEmpty()) {
                batch.shader = dissolveShader
                dissolveEntities.forEach { e -> renderDissolvedEntity(e) }
                batch.shader = null
            }
        }
    }

    private fun renderDissolvedEntity(entity: Entity) {
        val (_, uvOffset, uvMax, numFragments, value) = entity[Dissolve]
        val (sprite) = entity[Graphic]

        dissolveShader.setUniformf(ShaderAssets.DISSOLVE_VALUE, value)
        dissolveShader.setUniformf(ShaderAssets.DISSOLVE_UV_OFFSET, uvOffset)
        dissolveShader.setUniformf(ShaderAssets.DISSOLVE_ATLAS_MAX_UV, uvMax)
        dissolveShader.setUniformf(ShaderAssets.DISSOLVE_FRAG_NUMBER, numFragments)

        sprite.draw(batch)
    }

    override fun onTickEntity(entity: Entity) {
        val (sprite) = entity[Graphic]
        val (position, size, rotation) = entity[Boundary]

        sprite.setBounds(position.x, position.y, size.x, size.y)
        sprite.setOriginCenter()
        sprite.rotation = rotation

        if (entity has Dissolve) {
            // dissolved entities get rendered separately to avoid multiple flushes due to shader changes
            dissolveEntities += entity
            return
        }
        sprite.draw(batch)
    }

    override fun onEvent(event: Event) {
        when (event) {
            is MapLoadEvent -> mapRenderer.map = event.dungeonMap.tiledMap
            is MapTransitionStartEvent -> onMapTransitionStart(event)

            else -> Unit
        }
    }

    private fun onMapTransitionStart(event: MapTransitionStartEvent) {
        transitionMapRenderer.map = event.toMap.tiledMap
        val scaledMapWidth = event.toMap.tiledMap.totalWidth() * UNIT_SCALE
        val scaledMapHeight = event.toMap.tiledMap.totalHeight() * UNIT_SCALE
        val mapDiff = vec2(
            (event.fromConnection.x - event.toConnection.x) * UNIT_SCALE,
            (event.fromConnection.y - event.toConnection.y) * UNIT_SCALE
        )
        val (camX, camY, camW, camH) = orthoCamera
        val distToPan = vec2(min(scaledMapWidth, camW), min(scaledMapHeight, camH))

        doTransition = true
        transitionAlpha = 0f
        transitionInterpolation = Interpolation.fade

        transitionFrom.set(camX, camY)
        transitionTo.set(transitionFrom)

        when (event.connectionType) {
            ConnectionType.LEFT -> {
                transitionTo.x -= distToPan.x
                transitionTo.y += mapDiff.y
                transitionOffset.set(-scaledMapWidth, -mapDiff.y)
            }

            ConnectionType.RIGHT -> {
                transitionTo.x += distToPan.x
                transitionTo.y += mapDiff.y
                transitionOffset.set(scaledMapWidth, -mapDiff.y)
            }

            ConnectionType.DOWN -> {
                transitionTo.x += mapDiff.x
                transitionTo.y -= distToPan.y
                transitionOffset.set(mapDiff.x, scaledMapHeight)
            }

            ConnectionType.UP -> {
                transitionTo.x += mapDiff.x
                transitionTo.y += distToPan.y
                transitionOffset.set(mapDiff.x, -scaledMapHeight)
            }
        }
    }

    override fun onDispose() {
        mapRenderer.disposeSafely()
        transitionMapRenderer.disposeSafely()
    }

    private operator fun OrthographicCamera.component1() = this.position.x

    private operator fun OrthographicCamera.component2() = this.position.y

    private operator fun OrthographicCamera.component3() = this.viewportWidth

    private operator fun OrthographicCamera.component4() = this.viewportHeight

    companion object {
        const val TRANSITION_SPEED = 0.8f
    }
}

