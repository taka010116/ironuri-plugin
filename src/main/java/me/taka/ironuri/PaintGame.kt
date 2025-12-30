package me.taka.paintgame

import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.boss.*
import org.bukkit.command.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.*
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import kotlin.math.*
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.meta.Damageable
import org.bukkit.event.block.BlockBreakEvent


class PaintGamePlugin : JavaPlugin(), Listener {

    private val colors = listOf(
        DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW
    )

    private val playerColor = mutableMapOf<UUID, DyeColor>()
    private val colorCount = mutableMapOf<DyeColor, Int>()

    private var countBar: BossBar? = null
    private var ratioBar: BossBar? = null
    private var gameRunning = false

    private var gameTime = 0
    private var maxGameTime = 0

    private val originalBlocks = mutableMapOf<Location, Material>()
    private var gameTask: BukkitRunnable? = null
    // スニーク5秒判定用
    private val sneakTasks = mutableMapOf<UUID, BukkitRunnable>()


    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        registerRecipes()
    }

    /* ---------- start コマンド ---------- */
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        when (command.name.lowercase()) {
            "start" -> {
                val min = args.getOrNull(0)?.toIntOrNull() ?: return false
                startGame(min)
                return true
            }

            "reset" -> {
                resetWorld()
                sender.sendMessage("§aワールドをリセットしました")
                return true
            }

            "finish" -> {
                finishGame()
                return true
            }

            "light" -> {
                enableGlow()
                sender.sendMessage("§e全プレイヤーを発光させました")
                return true
            }

        }
        return false
    }

    private fun enableGlow() {
        Bukkit.getOnlinePlayers().forEach { p ->
            p.isGlowing = true
        }
    }

    private fun resetWorld() {
        originalBlocks.forEach { (loc, mat) ->
            loc.block.type = mat
        }
        originalBlocks.clear()
    }

    private fun finishGame() {
        gameRunning = false
        gameTask?.cancel()

        countBar?.removeAll()
        ratioBar?.removeAll()
        countBar = null
        ratioBar = null

        // ✨ 発光OFF
        Bukkit.getOnlinePlayers().forEach {
            it.isGlowing = false
        }

        val counts = countWool()
        val winner = counts.maxByOrNull { it.value }

        Bukkit.broadcastMessage("§6=== 試合終了 ===")
        counts.forEach {
            Bukkit.broadcastMessage("§f${it.key.name}: ${it.value}")
        }
        winner?.let {
            Bukkit.broadcastMessage("§a勝者: ${it.key.name} チーム！")
        }
    }



    private fun countWool(): Map<DyeColor, Int> {
        val result = mutableMapOf<DyeColor, Int>()
        colors.forEach { result[it] = 0 }

        originalBlocks.keys.forEach { loc ->
            val block = loc.block
            if (block.type.name.endsWith("_WOOL")) {
                val colorName = block.type.name.removeSuffix("_WOOL")
                val color = DyeColor.valueOf(colorName)
                result[color] = result[color]!! + 1
            }
        }
        return result
    }


    private fun startGame(min: Int) {
        maxGameTime = min * 60
        gameTime = maxGameTime
        gameRunning = true

        countBar = Bukkit.createBossBar("カウント", BarColor.WHITE, BarStyle.SOLID)
        ratioBar = Bukkit.createBossBar("割合", BarColor.WHITE, BarStyle.SEGMENTED_10)

        Bukkit.getOnlinePlayers().forEachIndexed { i, p ->
            val color = colors[i % colors.size]
            playerColor[p.uniqueId] = color

            countBar!!.addPlayer(p)
            ratioBar!!.addPlayer(p)

            // 🔥 発光ON
            p.isGlowing = true

            // 🎉 Title 表示
            p.sendTitle(
                "§6色塗り合戦",
                "§f制限時間 $min 分",
                10, 40, 10
            )
        }

        gameTask = object : BukkitRunnable() {
            override fun run() {
                if (gameTime <= 0) {
                    finishGame()
                    cancel()
                    return
                }
                updateBars()
                gameTime--
            }
        }
        gameTask!!.runTaskTimer(this, 0, 20)
    }

    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        if (!gameRunning) return

        val block = e.block

        // 空気・壊して意味のないものは除外
        if (block.type == Material.AIR) return

        // 初回のみ元のブロックを記録
        originalBlocks.putIfAbsent(
            block.location.clone(),
            block.type
        )
    }


    @EventHandler
    fun onSneak(e: PlayerToggleSneakEvent) {
        val p = e.player
        val uuid = p.uniqueId

        // スニーク開始
        if (e.isSneaking) {

            // すでに待機中なら何もしない
            if (sneakTasks.containsKey(uuid)) return

            val task = object : BukkitRunnable() {
                override fun run() {
                    // 5秒後もスニーク中なら
                    if (p.isSneaking) {
                        p.inventory.addItem(ItemStack(Material.SNOWBALL, 1))
                        p.playSound(p.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f)
                        p.sendMessage("§b雪玉を獲得しました！")

                        // 再取得防止（立つまで不可）
                        sneakTasks.remove(uuid)
                    }
                }
            }

            sneakTasks[uuid] = task
            task.runTaskLater(this, 20L * 5) // 5秒
        }
        // スニーク解除
        else {
            sneakTasks.remove(uuid)?.cancel()
        }
    }



    /* ---------- 歩いたら塗る ---------- */
    @EventHandler
    fun onMove(e: PlayerMoveEvent) {
        if (!gameRunning) return

        val p = e.player
        val item = p.inventory.itemInMainHand

        // ブラシを持っていなければ何もしない
        if (item.type != Material.BRUSH) return

        val to = e.to ?: return
        if (e.from.blockX == to.blockX &&
            e.from.blockY == to.blockY &&
            e.from.blockZ == to.blockZ) return

        val under = to.clone().add(0.0, -1.0, 0.0).block

        // 塗れたかどうか
        val painted = paintBlockWithBrush(under, p, item)

        if (painted) {
            damageBrush(p, item)
        }
    }

    private fun paintBlockWithBrush(block: Block, p: Player, item: ItemStack): Boolean {
        if (block.type == Material.AIR) return false
        if (block.type == Material.OBSIDIAN || block.type == Material.BEDROCK) return false

        val color = playerColor[p.uniqueId] ?: return false
        val wool = Material.valueOf("${color.name}_WOOL")

        if (block.type == wool) return false

        originalBlocks.putIfAbsent(block.location.clone(), block.type)
        block.type = wool
        return true
    }

    private fun damageBrush(p: Player, item: ItemStack) {
        val meta = item.itemMeta as? Damageable ?: return

        meta.damage += 1
        item.itemMeta = meta

        // 100マス塗ったら破壊
        if (meta.damage >= 100) {
            p.inventory.setItemInMainHand(null)
            p.playSound(p.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
            p.sendMessage("§cブラシが壊れました！")
        }
    }



    /* ---------- 雪玉 ---------- */
    @EventHandler
    fun onSnowball(e: ProjectileHitEvent) {
        val snow = e.entity as? Snowball ?: return
        val p = snow.shooter as? Player ?: return
        paintCube(e.hitBlock?.location ?: snow.location, 1, p)
    }

    /* ---------- TNT ---------- */
    @EventHandler
    fun onPlaceTNT(e: BlockPlaceEvent) {
        if (e.block.type != Material.TNT) return

        val loc = e.block.location.add(0.5, 0.0, 0.5)
        e.block.type = Material.AIR

        val world = loc.world ?: return
        val tnt = world.spawn(loc, TNTPrimed::class.java)

        //val tnt = loc.world.spawn(loc, TNTPrimed::class.java)
        tnt.fuseTicks = 40   // 2秒
        tnt.source = e.player
    }


    @EventHandler
    fun onTNTExplode(e: EntityExplodeEvent) {
        val tnt = e.entity as? TNTPrimed ?: return
        val p = tnt.source as? Player ?: return

        e.isCancelled = true        // ← 破壊を完全キャンセル
        //e.blockList().clear()       // ← 念のため

        paintSphere(tnt.location, 5, p)
    }



    /* ---------- 死亡 ---------- */
    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        val victim = e.entity
        val killer = victim.killer ?: return
        paintSphere(victim.location, 5, killer)
    }


    /* ---------- ペイント処理 ---------- */
    private fun paint(p: Player, block: Block) {
        paintBlock(block, p)
    }

    private fun paintBlock(block: Block, p: Player) {
        if (block.type == Material.AIR) return

        val color = playerColor[p.uniqueId] ?: return
        val wool = Material.valueOf("${color.name}_WOOL")

        if (block.type == wool) return

        // 初回のみ元のブロックを記録
        originalBlocks.putIfAbsent(block.location.clone(), block.type)

        block.type = wool
    }


    private fun paintCube(loc: Location, r: Int, p: Player) {
        for (x in -r..r)
            for (y in 0..r)
                for (z in -r..r)
                    paintBlock(
                        loc.clone().add(x.toDouble(), y.toDouble(), z.toDouble()).block,
                        p
                    )
    }

    private fun paintSphere(loc: Location, r: Int, p: Player) {
        for (x in -r..r)
            for (z in -r..r)
                if (x * x + z * z <= r * r)
                    paintBlock(
                        loc.world!!.getHighestBlockAt(
                            loc.blockX + x,
                            loc.blockZ + z
                        ),
                        p
                    )
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        if (!gameRunning) return
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            e.player.isGlowing = true
        }, 1L)
    }


    /* ---------- BossBar ---------- */
    private fun updateBars() {
        val counts = countWool()
        val total = counts.values.sum().coerceAtLeast(1)

        // 🔢 数値バー
        val text = counts.entries.joinToString("  ") {
            "${it.key.name}:${it.value}"
        }
        countBar?.setTitle(text)
        countBar?.setProgress(gameTime.toDouble() / maxGameTime)

        // 📊 割合バー（最大色を表示）
        val max = counts.maxByOrNull { it.value } ?: return
        ratioBar?.setTitle(
            "${max.key.name} ${(max.value * 100 / total)}%"
        )
        ratioBar?.setProgress(max.value.toDouble() / total)
    }



    /* ---------- レシピ ---------- */
    private fun registerRecipes() {

        // 雪玉：板材（全種類OK）9個 → 雪玉 8個
        val snow = ShapedRecipe(
            NamespacedKey(this, "snow"),
            ItemStack(Material.SNOWBALL, 8)
        )
        snow.shape("PPP", "PPP", "PPP")
        snow.setIngredient(
            'P',
            RecipeChoice.MaterialChoice(
                Material.OAK_PLANKS,
                Material.SPRUCE_PLANKS,
                Material.BIRCH_PLANKS,
                Material.JUNGLE_PLANKS,
                Material.ACACIA_PLANKS,
                Material.DARK_OAK_PLANKS,
                Material.MANGROVE_PLANKS,
                Material.CHERRY_PLANKS,
                Material.BAMBOO_PLANKS,
                Material.CRIMSON_PLANKS,
                Material.WARPED_PLANKS
            )
        )
        Bukkit.addRecipe(snow)

        // TNT：原木（全種類OK）9個 → TNT 1個
        val tnt = ShapedRecipe(
            NamespacedKey(this, "tnt"),
            ItemStack(Material.TNT, 1)
        )
        tnt.shape("LLL", "LLL", "LLL")
        tnt.setIngredient(
            'L',
            RecipeChoice.MaterialChoice(
                Material.OAK_LOG,
                Material.SPRUCE_LOG,
                Material.BIRCH_LOG,
                Material.JUNGLE_LOG,
                Material.ACACIA_LOG,
                Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG,
                Material.CHERRY_LOG
            )
        )
        Bukkit.addRecipe(tnt)

        // ステーキ：木のハーフブロック（全種類OK）9個 → ステーキ 4個
        val beef = ShapedRecipe(
            NamespacedKey(this, "beef"),
            ItemStack(Material.COOKED_BEEF, 4)
        )
        beef.shape("SSS", "SSS", "SSS")
        beef.setIngredient(
            'S',
            RecipeChoice.MaterialChoice(
                Material.OAK_SLAB,
                Material.SPRUCE_SLAB,
                Material.BIRCH_SLAB,
                Material.JUNGLE_SLAB,
                Material.ACACIA_SLAB,
                Material.DARK_OAK_SLAB,
                Material.MANGROVE_SLAB,
                Material.CHERRY_SLAB,
                Material.BAMBOO_SLAB,
                Material.CRIMSON_SLAB,
                Material.WARPED_SLAB
            )
        )
        Bukkit.addRecipe(beef)

        // ブラシ：原木5個
        val brush = ShapedRecipe(
            NamespacedKey(this, "brush"),
            ItemStack(Material.BRUSH)
        )

        brush.shape(
            " L ",
            " L ",
            " L "
        )

        brush.setIngredient(
            'L',
            RecipeChoice.MaterialChoice(
                Material.OAK_LOG,
                Material.SPRUCE_LOG,
                Material.BIRCH_LOG,
                Material.JUNGLE_LOG,
                Material.ACACIA_LOG,
                Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG,
                Material.CHERRY_LOG
            )
        )

        Bukkit.addRecipe(brush)
    }

}
