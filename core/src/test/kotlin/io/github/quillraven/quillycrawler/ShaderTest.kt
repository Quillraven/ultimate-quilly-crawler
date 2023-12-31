package io.github.quillraven.quillycrawler

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.Viewport
import io.github.quillraven.quillycrawler.QuillyCrawler.Companion.UNIT_SCALE
import io.github.quillraven.quillycrawler.QuillyCrawler.Companion.gameViewport
import io.github.quillraven.quillycrawler.assets.Assets
import io.github.quillraven.quillycrawler.assets.ShaderAssets
import io.github.quillraven.quillycrawler.assets.TextureAtlasAssets
import ktx.assets.disposeSafely
import ktx.graphics.use
import ktx.math.vec2

class ShaderTest : ApplicationAdapter() {

    private val viewport: Viewport = gameViewport()
    private val batch: Batch by lazy { SpriteBatch() }
    private val assets = Assets()
    private val shaderSprite = Sprite()
    private val normalSprite = Sprite()
    private val dissolveShader by lazy { assets.loadShader(ShaderAssets.DISSOLVE) }
    private var shaderDissolve = 0f
    private var dissolveDirection = 1f

    override fun create() {
        val atlas = assets.load<TextureAtlas>(TextureAtlasAssets.CHARACTERS.path)
        with(shaderSprite) {
            setRegion(atlas.findRegion("priest/idle", 0))
            setPosition(2f, 2f)
            setSize(1f, 1f)
        }
        with(normalSprite) {
            setRegion(atlas.findRegion("priest/idle", 0))
            setPosition(6f, 2f)
            setSize(1f, 1f)
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f, true)

        viewport.apply()
        batch.use(viewport.camera) {
            if (it.shader == dissolveShader) {
                it.shader.use {
                    val deltaTime = Gdx.graphics.deltaTime
                    shaderDissolve = (shaderDissolve + dissolveDirection * deltaTime * 0.5f).coerceIn(0f, 1f)
                    shaderSprite.setScale(1f + shaderDissolve, 1f + shaderDissolve)
                    shaderSprite.setOriginCenter()

                    val scaledSpriteSize = vec2(shaderSprite.width / UNIT_SCALE, shaderSprite.height / UNIT_SCALE)
                    dissolveShader.setUniformf("u_dissolve", shaderDissolve)
                    dissolveShader.setUniformf("u_uvOffset", vec2(shaderSprite.u, shaderSprite.v))
                    dissolveShader.setUniformf("u_atlasMaxUV", vec2(shaderSprite.u2, shaderSprite.v2))
                    dissolveShader.setUniformf("u_fragmentNumber", scaledSpriteSize)

                    if (shaderDissolve >= 1f || shaderDissolve <= 0f) {
                        dissolveDirection = -dissolveDirection
                    }
                }
            }
            shaderSprite.draw(it)

            val shader = it.shader
            it.shader = null
            normalSprite.draw(it)
            it.shader = shader
        }

        when {
            Gdx.input.isKeyJustPressed(Input.Keys.NUM_1) -> batch.shader = null
            Gdx.input.isKeyJustPressed(Input.Keys.NUM_2) -> batch.shader = dissolveShader
        }

        Gdx.graphics.setTitle("ShaderTest FPS: " + Gdx.graphics.framesPerSecond)
    }

    override fun dispose() {
        batch.disposeSafely()
        assets.dispose()
    }
}
