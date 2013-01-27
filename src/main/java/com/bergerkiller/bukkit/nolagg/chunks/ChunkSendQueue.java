package com.bergerkiller.bukkit.nolagg.chunks;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import com.bergerkiller.bukkit.common.IntRemainder;
import com.bergerkiller.bukkit.common.reflection.SafeField;
import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.reflection.classes.EntityPlayerRef;
import com.bergerkiller.bukkit.common.reflection.classes.NetworkManagerRef;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.NativeUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.nolagg.NoLagg;
import com.bergerkiller.bukkit.nolagg.NoLaggComponents;
import com.bergerkiller.bukkit.nolagg.examine.PluginLogger;
import net.minecraft.server.v1_4_6.ChunkCoordIntPair;
import net.minecraft.server.v1_4_6.EntityPlayer;
import net.minecraft.server.v1_4_6.INetworkManager;
import net.minecraft.server.v1_4_6.NetworkManager;
import net.minecraft.server.v1_4_6.Packet;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class ChunkSendQueue extends ChunkSendQueueBase {
	private static final long serialVersionUID = 1L;
	public static double maxRate = 2;
	public static double minRate = 0.25;
	public static double compressBusyPercentage = 0.0;
	private static long prevtime;
	private static Task task;

	private static class ChunkLoadingTask extends Task {
		public ChunkLoadingTask() {
			super(NoLagg.plugin);
		}

		@Override
		public void run() {
			try {
				double newper = ChunkCompressionThread.getBusyPercentage(System.currentTimeMillis() - prevtime);
				compressBusyPercentage = MathUtil.useOld(compressBusyPercentage, newper * 100.0, 0.1);
				prevtime = System.currentTimeMillis();
				for (Player player : CommonUtil.getOnlinePlayers()) {
					ChunkSendQueue queue = bind(player);
					queue.updating.next(true);
					queue.update();
					queue.updating.reset(false);
				}
			} catch (Exception ex) {
				NoLaggChunks.plugin.log(Level.SEVERE, "An error occured while sending chunks:");
				ex.printStackTrace();
			} catch (OutOfMemoryError ex) {
				NoLaggChunks.plugin.log(Level.SEVERE, "We are running out of memory here!");
				NoLaggChunks.plugin.log(Level.SEVERE, "Restart the server and increase the RAM usage available for Bukkit.");
			}
		}
	}

	public static void init() {
		if (!NetworkManagerRef.queueSize.isValid()) {
			NoLaggChunks.plugin.log(Level.SEVERE, "Failed to hook into the player packet queue size field");
			NoLaggChunks.plugin.log(Level.SEVERE, "Distortions in the chunk rate will cause players to get kicked");
		}
		prevtime = System.currentTimeMillis();
		task = new ChunkLoadingTask().start(1, 1);
	}

	public static void deinit() {
		Task.stop(task);
		task = null;
		// clear bound queues
		for (Player player : CommonUtil.getOnlinePlayers()) {
			ChunkSendQueue queue = bind(player);
			if (queue != null) {
				EntityPlayerRef.chunkQueue.set(NativeUtil.getNative(player), queue.toLinkedList());
			}
		}
	}

	public static ChunkSendQueue bind(Player with) {
		EntityPlayer ep = NativeUtil.getNative(with);
		if (!(ep.chunkCoordIntPairQueue instanceof ChunkSendQueue)) {
			ChunkSendQueue queue = new ChunkSendQueue(with);
			ep.chunkCoordIntPairQueue.clear();
			EntityPlayerRef.chunkQueue.set(ep, queue);
		}
		return (ChunkSendQueue) ep.chunkCoordIntPairQueue;
	}

	public final Player player;
	private int idleTicks = 0;
	public BlockFace sendDirection = BlockFace.NORTH;
	public World world;
	public int x;
	public int z;
	private IntRemainder rate = new IntRemainder(2.0, 1);
	private int intervalcounter = 200;
	private ChunkCompressQueue chunkQueue;

	/*
	 * Packet queue related variables
	 */
	private int prevQueueSize = 0;
	private int maxQueueSize = 300000;
	private int packetBufferQueueSize = 0;
	private int buffersizeavg = 0;

	private ChunkSendQueue(final Player player) {
		this.player = player;
		this.world = player.getWorld();
		this.sendDirection = null; // Force a sorting operation the next tick
		EntityPlayer ep = NativeUtil.getNative(player);
		this.x = (int) (ep.locX + ep.motX * 16) >> 4;
		this.z = (int) (ep.locZ + ep.motZ * 16) >> 4;
		this.chunkQueue = new ChunkCompressQueue(this);
		this.addAll(ep.chunkCoordIntPairQueue);
		this.add(new ChunkCoordIntPair(MathUtil.toChunk(ep.locX), MathUtil.toChunk(ep.locZ)));
		ChunkCompressionThread.addQueue(this.chunkQueue);
		this.enforceBufferFullSize();
	}

	private void enforceBufferFullSize() {
		INetworkManager nm = NativeUtil.getNative(this.player).playerConnection.networkManager;
		Object lockObject = new SafeField<Object>(NetworkManager.class, "h").get(nm);
		if (lockObject != null) {
			List<Packet> low = new SafeField<List<Packet>>(NetworkManager.class, "lowPriorityQueue").get(nm);
			List<Packet> high = new SafeField<List<Packet>>(NetworkManager.class, "highPriorityQueue").get(nm);
			if (low != null && high != null) {
				int queuedsize = 0;
				synchronized (lockObject) {
					for (Packet p : low)
						queuedsize += p.a() + 1;
					for (Packet p : high)
						queuedsize += p.a() + 1;
					NetworkManagerRef.queueSize.set(nm, queuedsize - 9437184);
				}
			}
		}
	}

	@Override
	public int getCenterX() {
		return this.x;
	}

	@Override
	public int getCenterZ() {
		return this.z;
	}

	public static double getAverageRate() {
		double totalrate = 0;
		int pcount = 0;
		for (Player player : CommonUtil.getOnlinePlayers()) {
			totalrate += bind(player).rate.get();
			pcount++;
		}
		return totalrate / (double) pcount;
	}

	public double getRate() {
		return this.rate.get();
	}

	public String getBufferLoadMsg() {
		double per = MathUtil.round(100D * this.buffersizeavg / getMaxQueueSize(), 2);
		if (this.buffersizeavg > 300000) {
			return ChatColor.RED.toString() + per + "%";
		} else if (this.buffersizeavg > 100000) {
			return ChatColor.GOLD.toString() + per + "%";
		} else {
			return ChatColor.GREEN.toString() + per + "%";
		}
	}

	@Override
	public void sort() {
		super.sort();
		this.chunkQueue.sort();
		synchronized (this) {
			this.updating.next(true);
			this.sort(this);
			this.updating.previous();
		}
	}

	public void sort(List elements) {
		if (elements.isEmpty()) {
			return;
		}
		ChunkCoordIntPair middle = new ChunkCoordIntPair(this.x, this.z);
		try {
			Collections.sort(elements, ChunkCoordComparator.get(this.sendDirection, middle));
		} catch (ConcurrentModificationException ex) {
			NoLaggChunks.plugin.log(Level.SEVERE, "Another plugin interfered while sorting a collection!");
		} catch (ArrayIndexOutOfBoundsException ex) {
			NoLaggChunks.plugin.log(Level.SEVERE, "Another plugin interfered while sorting a collection!");
		} catch (Throwable t) {
			NoLaggChunks.plugin.log(Level.SEVERE, "An error occurred while sorting a collection:");
			t.printStackTrace();
		}
	}

	/**
	 * Main update routine - handles the calculation of the rate and interval
	 * and updates afterwards
	 */
	private void update() {
		// Update queue size
		if (NetworkManagerRef.queueSize.isValid()) {
			this.packetBufferQueueSize = (Integer) NetworkManagerRef.queueSize.get(NativeUtil.getNative(this.player).playerConnection.networkManager);
			this.packetBufferQueueSize += 9437184;
		}
		// Update current buffer size
		if (this.buffersizeavg == 0) {
			this.buffersizeavg = this.packetBufferQueueSize;
		} else {
			this.buffersizeavg += 0.3 * (this.packetBufferQueueSize - this.buffersizeavg);
		}
		// Idling
		if (this.idleTicks > 0) {
			this.idleTicks--;
			return;
		}

		if (this.isEmpty() && !this.chunkQueue.canSend()) {
			// Queue some remaining chunks?
			this.verifySentChunks();
			if (this.isEmpty()) {
				return;
			}
		}
		double newrate = this.rate.get();
		if (this.packetBufferQueueSize > this.maxQueueSize) {
			newrate = minRate;
		} else {
			if (this.prevQueueSize > this.packetBufferQueueSize) {
				newrate += 0.07;
			} else {
				// to force the rate to be optimal
				if (this.packetBufferQueueSize > 80000) {
					newrate -= 0.17;
				} else if (this.packetBufferQueueSize > 20000) {
					newrate -= 0.14;
				} else {
					newrate += 0.06;
				}
			}
			newrate += 0.9 * (this.rate.get() - newrate);
			// set rate bounds
			if (newrate > maxRate) {
				newrate = maxRate;
			} else if (newrate < minRate) {
				newrate = minRate;
			}
		}

		this.rate.set(newrate);
		this.prevQueueSize = this.packetBufferQueueSize;
		// send chunks
		if (newrate >= 1) {
			this.update(1, (int) this.rate.next());
		} else {
			this.update((int) (1 / this.rate.get()), 1);
		}
	}

	/**
	 * Performs sorting and batch sending at the interval and rate settings
	 * specified
	 * 
	 * @param interval
	 *            to send at
	 * @param rate
	 *            to send at
	 */
	private void update(int interval, int rate) {
		if (interval == 0)
			interval = 1;
		if (rate == 0)
			return;
		if (this.intervalcounter >= interval) {
			EntityPlayer ep = NativeUtil.getNative(this.player);
			updatePosition(this.player.getWorld(), ep.locX + ep.motX * 16, ep.locZ + ep.motZ * 16, ep.yaw);
			this.sendBatch(rate);
			this.intervalcounter = 1;
		} else {
			this.intervalcounter++;
		}
	}

	/**
	 * Updates the position of this queue for the player
	 * 
	 * @param position to set to
	 */
	public void updatePosition(Location position) {
		updatePosition(position.getWorld(), position.getX(), position.getZ(), position.getYaw());
	}

	/**
	 * Updates the position of this queue for the player
	 * 
	 * @param world to set to
	 * @param locX to set to
	 * @param locZ to set to
	 * @param yaw to set to
	 */
	public void updatePosition(World world, double locX, double locZ, float yaw) {
		BlockFace newDirection = FaceUtil.yawToFace(yaw - 90.0F);
		int newx = MathUtil.toChunk(locX);
		int newz = MathUtil.toChunk(locZ);
		if (world != this.world || newx != this.x || newz != this.z || this.sendDirection != newDirection) {
			if (this.world != world) { 
				setOldUnloaded();
			}
			this.sendDirection = newDirection;
			this.x = newx;
			this.z = newz;
			this.world = world;
			this.sort();
		}
	}

	/**
	 * Prepares the given amount of chunks for sending and flushed compressed
	 * chunks
	 * 
	 * @param count of chunks to load
	 */
	private void sendBatch(int count) {
		// load chunks
		long start = System.nanoTime();
		for (int i = 0; i < count; i++) {
			ChunkCoordIntPair pair = this.pollNextChunk();
			if (pair == null) {
				break;
			}
			this.chunkQueue.enqueue(WorldUtil.getChunk(this.player.getWorld(), pair.x, pair.z));
		}
		// Filter the chunk load times to prevent duplication in the examiner
		if (NoLaggComponents.EXAMINE.isEnabled()) {
			if (PluginLogger.isRunning()) {
				// Subtract time from chunk loading task
				PluginLogger.getTask(task, NoLagg.plugin).subtractTime(start);
			}
		}

		// send chunks
		for (int i = 0; i < count; i++) {
			if (!this.chunkQueue.sendNext()) {
				// Wait a few ticks to make chunks visible
				this.idle(4);
				break;
			}
		}
	}

	/**
	 * Waits the amount of ticks specified, doing nothing
	 * 
	 * @param ticks
	 *            to wait
	 */
	public void idle(int ticks) {
		this.idleTicks += ticks;
	}

	private int getMaxQueueSize() {
		return 10485760;
	}

	/**
	 * Gets the remaining chunks that need sending
	 * 
	 * @return to send size
	 */
	public int getPendingSize() {
		return super.size() + this.chunkQueue.getPendingSize();
	}

	@Override
	public boolean remove(ChunkCoordIntPair pair) {
		return super.remove(pair) || this.chunkQueue.remove(pair.x, pair.z);
	}

	@Override
	public boolean isNear(final int chunkx, final int chunkz, final int view) {
		return EntityUtil.isNearChunk(this.player, chunkx, chunkz, view + 1);
	}

	@Override
	protected boolean add(ChunkCoordIntPair pair) {
		if (super.add(pair)) {
			this.chunkQueue.remove(pair.x, pair.z);
			this.sendDirection = null; // invalidate
			return true;
		} else {
			return false;
		}
	}

}
