package org.unichain.core.services;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.unichain.common.application.Application;
import org.unichain.common.application.Service;
import org.unichain.common.application.UnichainApplicationContext;
import org.unichain.common.backup.BackupManager;
import org.unichain.common.backup.BackupManager.BackupStatusEnum;
import org.unichain.common.backup.BackupServer;
import org.unichain.common.crypto.ECKey;
import org.unichain.common.utils.ByteArray;
import org.unichain.common.utils.StringUtil;
import org.unichain.core.capsule.AccountCapsule;
import org.unichain.core.capsule.BlockCapsule;
import org.unichain.core.capsule.WitnessCapsule;
import org.unichain.core.config.Parameter.ChainConstant;
import org.unichain.core.config.args.Args;
import org.unichain.core.db.Manager;
import org.unichain.core.exception.*;
import org.unichain.core.net.UnichainNetService;
import org.unichain.core.net.message.BlockMessage;
import org.unichain.core.witness.BlockProductionCondition;
import org.unichain.core.witness.WitnessController;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.unichain.core.witness.BlockProductionCondition.NOT_MY_TURN;

@Slf4j(topic = "witness")
public class WitnessService implements Service {

  private static final int MIN_PARTICIPATION_RATE = Args.getInstance()
      .getMinParticipationRate(); // MIN_PARTICIPATION_RATE * 1%
  private static final int PRODUCE_TIME_OUT = 500; // ms
  @Getter
  private static volatile boolean needSyncCheck = Args.getInstance().isNeedSyncCheck();

  private Application unichainApp;
  @Getter
  protected Map<ByteString, WitnessCapsule> localWitnessStateMap = Maps
      .newHashMap(); //  <witnessAccountAddress,WitnessCapsule>
  private Thread generateThread;

  @Getter
  private volatile boolean isRunning = false;
  private Map<ByteString, byte[]> privateKeyMap = Maps
      .newHashMap();//<witnessAccountAddress,privateKey>
  private Map<byte[], byte[]> privateKeyToAddressMap = Maps
      .newHashMap();//<privateKey,witnessPermissionAccountAddress>

  private Manager manager;

  private WitnessController controller;

  private UnichainApplicationContext context;

  private BackupManager backupManager;

  private BackupServer backupServer;

  private UnichainNetService unichainNetService;

  private AtomicInteger dupBlockCount = new AtomicInteger(0);
  private AtomicLong dupBlockTime = new AtomicLong(0);
  private long blockCycle =
      ChainConstant.BLOCK_PRODUCED_INTERVAL * ChainConstant.MAX_ACTIVE_WITNESS_NUM;
  private Cache<ByteString, Long> blocks = CacheBuilder.newBuilder().maximumSize(10).build();

  /**
   * Construction method.
   */
  public WitnessService(Application unichainApp, UnichainApplicationContext context) {
    this.unichainApp = unichainApp;
    this.context = context;
    backupManager = context.getBean(BackupManager.class);
    backupServer = context.getBean(BackupServer.class);
    unichainNetService = context.getBean(UnichainNetService.class);
    generateThread = new Thread(scheduleProductionLoop);
    manager = unichainApp.getDbManager();
    manager.setWitnessService(this);
    controller = manager.getWitnessController();
    new Thread(() -> {
      while (needSyncCheck) {
        try {
          Thread.sleep(100);
        } catch (Exception e) {
        }
      }
      backupServer.initServer();
    }).start();
  }

  /**
   * Cycle thread to generate blocks
   */
  private Runnable scheduleProductionLoop =
      () -> {
        if (localWitnessStateMap == null || localWitnessStateMap.keySet().isEmpty()) {
          logger.error("LocalWitnesses is null");
          return;
        }

        while (isRunning) {
          try {
            if (this.needSyncCheck) {
              Thread.sleep(500L);
            } else {
              DateTime time = DateTime.now();
              long timeToNextSecond = ChainConstant.BLOCK_PRODUCED_INTERVAL
                  - (time.getSecondOfMinute() * 1000 + time.getMillisOfSecond())
                  % ChainConstant.BLOCK_PRODUCED_INTERVAL;
              if (timeToNextSecond < 50L) {
                timeToNextSecond = timeToNextSecond + ChainConstant.BLOCK_PRODUCED_INTERVAL;
              }
              DateTime nextTime = time.plus(timeToNextSecond);
              logger.debug("ProductionLoop sleep : " + timeToNextSecond + " ms,next time:" + nextTime);
              Thread.sleep(timeToNextSecond);
            }
            this.blockProductionLoop();
          } catch (Throwable throwable) {
            logger.error("Unknown throwable happened in witness loop", throwable);
          }
        }
      };

  /**
   * Loop to generate blocks
   */
  private void blockProductionLoop() throws InterruptedException {
    BlockProductionCondition result = this.tryProduceBlock();
    if (result == null) {
      logger.warn("Result is null");
      return;
    }

    if (result.ordinal() <= NOT_MY_TURN.ordinal()) {
      logger.debug(result.toString());
    } else {
      logger.info(result.toString());
    }
  }

  /**
   * Generate and broadcast blocks
   */
  private BlockProductionCondition tryProduceBlock() throws InterruptedException {
    logger.info("Try to produce block");
    long now = DateTime.now().getMillis() + 50L;
    if (this.needSyncCheck) {
      long nexSlotTime = controller.getSlotTime(1);
      if (nexSlotTime > now) { //check sync during first loop
        needSyncCheck = false;
        Thread.sleep(nexSlotTime - now); //Processing Time Drift later
        now = DateTime.now().getMillis();
      } else {
        logger.debug("Not sync ,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
            new DateTime(now),
            new DateTime(this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()),
            this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
            this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
        return BlockProductionCondition.NOT_SYNCED;
      }
    }

    if (!backupManager.getStatus().equals(BackupStatusEnum.MASTER)) {
      return BlockProductionCondition.BACKUP_STATUS_IS_NOT_MASTER;
    }

    if (dupWitnessCheck()) {
      return BlockProductionCondition.DUP_WITNESS;
    }

    final int participation = this.controller.calculateParticipationRate();
    if (participation < MIN_PARTICIPATION_RATE) {
      logger.warn("Participation[" + participation + "] <  MIN_PARTICIPATION_RATE[" + MIN_PARTICIPATION_RATE + "]");

      if (logger.isDebugEnabled()) {
        this.controller.dumpParticipationLog();
      }

      return BlockProductionCondition.LOW_PARTICIPATION;
    }

    if (!controller.activeWitnessesContain(this.getLocalWitnessStateMap().keySet())) {
      logger.info("Unelected. Elected Witnesses: {}", StringUtil.getAddressStringList(controller.getActiveWitnesses()));
      return BlockProductionCondition.UNELECTED;
    }

    try {
      BlockCapsule block;
      synchronized (unichainApp.getDbManager()) {
        long slot = controller.getSlotAtTime(now);
        logger.debug("Slot:" + slot);
        if (slot == 0) {
          logger.info("Not time yet,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
              new DateTime(now),
              new DateTime(this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()),
              this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
              this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
          return BlockProductionCondition.NOT_TIME_YET;
        }

        if (now < controller.getManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()) {
          logger.warn("have a timestamp:{} less than or equal to the previous block:{}", new DateTime(now), new DateTime(this.unichainApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()));
          return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
        }

        final ByteString scheduledWitness = controller.getScheduledWitness(slot);

        if (!this.getLocalWitnessStateMap().containsKey(scheduledWitness)) {
          logger.info("It's not my turn, ScheduledWitness[{}],slot[{}],abSlot[{}],",
              ByteArray.toHexString(scheduledWitness.toByteArray()), slot,
              controller.getAbSlotAtTime(now));
          return NOT_MY_TURN;
        }

        long scheduledTime = controller.getSlotTime(slot);

        if (scheduledTime - now > PRODUCE_TIME_OUT) {
          return BlockProductionCondition.LAG;
        }

        if (!privateKeyMap.containsKey(scheduledWitness)) {
          return BlockProductionCondition.NO_PRIVATE_KEY;
        }

        controller.getManager().lastHeadBlockIsMaintenance();

        controller.setGeneratingBlock(true);

        block = generateBlock(scheduledTime, scheduledWitness, controller.lastHeadBlockIsMaintenance());

        if (block == null) {
          logger.warn("exception when generate block");
          return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
        }

        int blockProducedTimeOut = Args.getInstance().getBlockProducedTimeOut();

        long timeout = Math.min(ChainConstant.BLOCK_PRODUCED_INTERVAL * blockProducedTimeOut / 100 + 500, ChainConstant.BLOCK_PRODUCED_INTERVAL);
        if (DateTime.now().getMillis() - now > timeout) {
          logger.warn("Task timeout ( > {}ms)，startTime:{},endTime:{}", timeout, new DateTime(now), DateTime.now());
          unichainApp.getDbManager().eraseBlock();
          return BlockProductionCondition.TIME_OUT;
        }
      }

      logger.info(
          "Produce block successfully, blockNumber:{}, abSlot[{}], blockId:{}, transactionSize:{}, blockTime:{}, parentBlockId:{}",
          block.getNum(), controller.getAbSlotAtTime(now), block.getBlockId(),
          block.getTransactions().size(),
          new DateTime(block.getTimeStamp()),
          block.getParentHash());

      broadcastBlock(block);

      return BlockProductionCondition.PRODUCED;
    } catch (UnichainException e) {
      logger.error(e.getMessage(), e);
      return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
    } finally {
      controller.setGeneratingBlock(false);
    }
  }

  //Verify that the private key corresponds to the witness permission
  public boolean validateWitnessPermission(ByteString scheduledWitness) {
    if (manager.getDynamicPropertiesStore().getAllowMultiSign() == 1) {
      byte[] privateKey = privateKeyMap.get(scheduledWitness);
      byte[] witnessPermissionAddress = privateKeyToAddressMap.get(privateKey);
      AccountCapsule witnessAccount = manager.getAccountStore()
          .get(scheduledWitness.toByteArray());
      if (!Arrays.equals(witnessPermissionAddress, witnessAccount.getWitnessPermissionAddress())) {
        return false;
      }
    }
    return true;
  }

  private void broadcastBlock(BlockCapsule block) {
    try {
      unichainNetService.broadcast(new BlockMessage(block.getData()));
    } catch (Exception ex) {
      throw new RuntimeException("BroadcastBlock error");
    }
  }

  private BlockCapsule generateBlock(long when, ByteString witnessAddress, Boolean lastHeadBlockIsMaintenance)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException {
    return unichainApp.getDbManager().generateBlock(this.localWitnessStateMap.get(witnessAddress), when, this.privateKeyMap.get(witnessAddress), lastHeadBlockIsMaintenance, true);
  }

  private boolean dupWitnessCheck() {
    if (dupBlockCount.get() == 0) {
      return false;
    }

    if (System.currentTimeMillis() - dupBlockTime.get() > dupBlockCount.get() * blockCycle) {
      dupBlockCount.set(0);
      return false;
    }

    return true;
  }

  public void checkDupWitness(BlockCapsule block) {
    if (block.generatedByMyself) {
      blocks.put(block.getBlockId().getByteString(), System.currentTimeMillis());
      return;
    }

    if (blocks.getIfPresent(block.getBlockId().getByteString()) != null) {
      return;
    }

    if (needSyncCheck) {
      return;
    }

    if (System.currentTimeMillis() - block.getTimeStamp() > ChainConstant.BLOCK_PRODUCED_INTERVAL) {
      return;
    }

    if (!privateKeyMap.containsKey(block.getWitnessAddress())) {
      return;
    }

    if (backupManager.getStatus() != BackupStatusEnum.MASTER) {
      return;
    }

    if (dupBlockCount.get() == 0) {
      dupBlockCount.set(new Random().nextInt(10));
    } else {
      dupBlockCount.set(10);
    }

    dupBlockTime.set(System.currentTimeMillis());

    logger.warn("Dup block produced: {}", block);
  }

  /**
   * Initialize the local witnesses
   */
  @Override
  public void init() {

    if (Args.getInstance().getLocalWitnesses().getPrivateKeys().size() == 0) {
      return;
    }

    byte[] privateKey = ByteArray
        .fromHexString(Args.getInstance().getLocalWitnesses().getPrivateKey());
    byte[] witnessAccountAddress = Args.getInstance().getLocalWitnesses()
        .getWitnessAccountAddress();
    //This address does not need to have an account
    byte[] privateKeyAccountAddress = ECKey.fromPrivate(privateKey).getAddress();

    WitnessCapsule witnessCapsule = this.unichainApp.getDbManager().getWitnessStore()
        .get(witnessAccountAddress);
    // need handle init witness
    if (null == witnessCapsule) {
      logger.warn("WitnessCapsule[" + witnessAccountAddress + "] is not in witnessStore");
      witnessCapsule = new WitnessCapsule(ByteString.copyFrom(witnessAccountAddress));
    }

    this.privateKeyMap.put(witnessCapsule.getAddress(), privateKey);
    this.localWitnessStateMap.put(witnessCapsule.getAddress(), witnessCapsule);
    this.privateKeyToAddressMap.put(privateKey, privateKeyAccountAddress);
  }

  @Override
  public void init(Args args) {
    //this.privateKey = args.getPrivateKeys();
    init();
  }

  @Override
  public void start() {
    isRunning = true;
    generateThread.start();

  }

  @Override
  public void stop() {
    isRunning = false;
    generateThread.interrupt();
  }
}
