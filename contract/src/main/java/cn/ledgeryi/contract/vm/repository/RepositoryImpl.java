package cn.ledgeryi.contract.vm.repository;

import cn.ledgeryi.chainbase.common.utils.DBConfig;
import cn.ledgeryi.chainbase.core.ChainBaseManager;
import cn.ledgeryi.chainbase.core.capsule.AccountCapsule;
import cn.ledgeryi.chainbase.core.capsule.BlockCapsule;
import cn.ledgeryi.chainbase.core.capsule.BytesCapsule;
import cn.ledgeryi.chainbase.core.capsule.ContractCapsule;
import cn.ledgeryi.chainbase.core.db.BlockIndexStore;
import cn.ledgeryi.chainbase.core.db.BlockStore;
import cn.ledgeryi.chainbase.core.db.KhaosDatabase;
import cn.ledgeryi.chainbase.core.store.*;
import cn.ledgeryi.common.core.exception.BadItemException;
import cn.ledgeryi.common.core.exception.ItemNotFoundException;
import cn.ledgeryi.common.core.exception.StoreException;
import cn.ledgeryi.common.runtime.vm.DataWord;
import cn.ledgeryi.common.utils.ByteUtil;
import cn.ledgeryi.common.utils.DecodeUtil;
import cn.ledgeryi.common.utils.Sha256Hash;
import cn.ledgeryi.contract.vm.program.Program;
import cn.ledgeryi.contract.vm.program.Storage;
import cn.ledgeryi.crypto.utils.Hash;
import cn.ledgeryi.protos.Protocol.AccountType;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.Strings;

import java.util.HashMap;

import static cn.ledgeryi.chainbase.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;

@Slf4j(topic = "Repository")
public class RepositoryImpl implements Repository {

  private StoreFactory storeFactory;
  @Getter
  private DynamicPropertiesStore dynamicPropertiesStore;
  @Getter
  private AccountStore accountStore;
  @Getter
  private CodeStore codeStore;
  @Getter
  private ContractStore contractStore;
  @Getter
  private StorageRowStore storageRowStore;
  @Getter
  private BlockStore blockStore;
  @Getter
  private KhaosDatabase khaosDb;
  @Getter
  private BlockIndexStore blockIndexStore;
  @Getter
  private CpuTimeUsedStore cpuTimeUsedStore;
  @Getter
  private StorageUsedStore storageUsedStore;

  private Repository parent = null;

  private HashMap<Key, Value> accountCache = new HashMap<>();
  private HashMap<Key, Value> codeCache = new HashMap<>();
  private HashMap<Key, Value> contractCache = new HashMap<>();
  private HashMap<Key, Value> dynamicPropertiesCache = new HashMap<>();
  private HashMap<Key, Storage> storageCache = new HashMap<>();
  private HashMap<Key, Value> cpuTimeConsumeCache = new HashMap<>();
  private HashMap<Key, Value> storageConsumeCache = new HashMap<>();

  public RepositoryImpl(StoreFactory storeFactory, RepositoryImpl repository) {
    init(storeFactory, repository);
  }

  public static RepositoryImpl createRoot(StoreFactory storeFactory) {
    return new RepositoryImpl(storeFactory, null);
  }

  protected void init(StoreFactory storeFactory, RepositoryImpl parent) {
    if (storeFactory != null) {
      this.storeFactory = storeFactory;
      ChainBaseManager manager = storeFactory.getChainBaseManager();
      dynamicPropertiesStore = manager.getDynamicPropertiesStore();
      accountStore = manager.getAccountStore();
      codeStore = manager.getCodeStore();
      contractStore = manager.getContractStore();
      storageRowStore = manager.getStorageRowStore();
      blockStore = manager.getBlockStore();
      khaosDb = manager.getKhaosDb();
      blockIndexStore = manager.getBlockIndexStore();
      cpuTimeUsedStore = manager.getCpuTimeUsedStore();
      storageUsedStore = manager.getStorageUsedStore();
    }
    this.parent = parent;
  }

  @Override
  public Repository newRepositoryChild() {
    return new RepositoryImpl(storeFactory, this);
  }

  @Override
  public AccountCapsule createAccount(byte[] address, AccountType type) {
    Key key = new Key(address);
    AccountCapsule account = new AccountCapsule(ByteString.copyFrom(address), type);
    accountCache.put(key, new Value(account.getData(), Type.VALUE_TYPE_CREATE));
    return account;
  }

  @Override
  public AccountCapsule createAccount(byte[] address, String accountName, AccountType type) {
    Key key = new Key(address);
    AccountCapsule account = new AccountCapsule(ByteString.copyFrom(address), ByteString.copyFromUtf8(accountName), type);
    accountCache.put(key, new Value(account.getData(), Type.VALUE_TYPE_CREATE));
    return account;
  }

  @Override
  public AccountCapsule getAccount(byte[] address) {
    Key key = new Key(address);
    if (accountCache.containsKey(key)) {
      return accountCache.get(key).getAccount();
    }

    AccountCapsule accountCapsule;
    if (parent != null) {
      accountCapsule = parent.getAccount(address);
    } else {
      accountCapsule = getAccountStore().get(address);
    }

    if (accountCapsule != null) {
      accountCache.put(key, Value.create(accountCapsule.getData()));
    }
    return accountCapsule;
  }

  @Override
  public BytesCapsule getDynamic(byte[] word) {
    Key key = Key.create(word);
    if (dynamicPropertiesCache.containsKey(key)) {
      return dynamicPropertiesCache.get(key).getDynamicProperties();
    }

    BytesCapsule bytesCapsule;
    if (parent != null) {
      bytesCapsule = parent.getDynamic(word);
    } else {
      try {
        bytesCapsule = getDynamicPropertiesStore().get(word);
      } catch (BadItemException | ItemNotFoundException e) {
        log.warn("Not found dynamic property:" + Strings.fromUTF8ByteArray(word));
        bytesCapsule = null;
      }
    }
    if (bytesCapsule != null) {
      dynamicPropertiesCache.put(key, Value.create(bytesCapsule.getData()));
    }
    return bytesCapsule;
  }

  @Override
  public void deleteContract(byte[] address) {
    getCodeStore().delete(address);
    getAccountStore().delete(address);
    getContractStore().delete(address);
  }

  @Override
  public void createContract(byte[] address, ContractCapsule contractCapsule) {
    Key key = Key.create(address);
    Value value = Value.create(contractCapsule.getData(), Type.VALUE_TYPE_CREATE);
    contractCache.put(key, value);
  }

  @Override
  public ContractCapsule getContract(byte[] address) {
    Key key = Key.create(address);
    if (contractCache.containsKey(key)) {
      return contractCache.get(key).getContract();
    }
    ContractCapsule contractCapsule;
    if (parent != null) {
      contractCapsule = parent.getContract(address);
    } else {
      contractCapsule = getContractStore().get(address);
    }
    if (contractCapsule != null) {
      contractCache.put(key, Value.create(contractCapsule.getData()));
    }
    return contractCapsule;
  }

  @Override
  public void updateContract(byte[] address, ContractCapsule contractCapsule) {
    Key key = Key.create(address);
    Value value = Value.create(contractCapsule.getData(), Type.VALUE_TYPE_DIRTY);
    contractCache.put(key, value);
  }

  @Override
  public void updateAccount(byte[] address, AccountCapsule accountCapsule) {
    Key key = Key.create(address);
    Value value = Value.create(accountCapsule.getData(), Type.VALUE_TYPE_DIRTY);
    accountCache.put(key, value);
  }

  @Override
  public void saveCode(byte[] address, byte[] code) {
    Key key = Key.create(address);
    Value value = Value.create(code, Type.VALUE_TYPE_CREATE);
    codeCache.put(key, value);
    ContractCapsule contract = getContract(address);
    byte[] codeHash = Hash.sha3(code);
    contract.setCodeHash(codeHash);
    updateContract(address, contract);
  }

  @Override
  public byte[] getCode(byte[] address) {
    Key key = Key.create(address);
    if (codeCache.containsKey(key)) {
      return codeCache.get(key).getCode().getData();
    }
    byte[] code;
    if (parent != null) {
      code = parent.getCode(address);
    } else {
      if (null == getCodeStore().get(address)) {
        code = null;
      } else {
        code = getCodeStore().get(address).getData();
      }
    }
    if (code != null) {
      codeCache.put(key, Value.create(code));
    }
    return code;
  }

  @Override
  public void putStorageValue(byte[] address, DataWord key, DataWord value) {
    if (getAccount(address) == null) {
      return;
    }
    Key addressKey = Key.create(address);
    Storage storage;
    if (storageCache.containsKey(addressKey)) {
      storage = storageCache.get(addressKey);
    } else {
      storage = getStorage(address);
      storageCache.put(addressKey, storage);
    }
    storage.put(key, value);
  }

  @Override
  public DataWord getStorageValue(byte[] address, DataWord key) {
    if (getAccount(address) == null) {
      return null;
    }
    Key addressKey = Key.create(address);
    Storage storage;
    if (storageCache.containsKey(addressKey)) {
      storage = storageCache.get(addressKey);
    } else {
      storage = getStorage(address);
      storageCache.put(addressKey, storage);
    }
    return storage.getValue(key);
  }

  @Override
  public Storage getStorage(byte[] address) {
    Key key = Key.create(address);
    if (storageCache.containsKey(key)) {
      return storageCache.get(key);
    }
    Storage storage;
    if (this.parent != null) {
      storage = parent.getStorage(address);
    } else {
      storage = new Storage(address, getStorageRowStore());
    }
    ContractCapsule contract = getContract(address);
    if (contract != null && !ByteUtil.isNullOrZeroArray(contract.getTxHash())) {
      storage.generateAddrHash(contract.getTxHash());
    }
    return storage;
  }

  @Override
  public void setParent(Repository repository) {
    parent = repository;
  }

  @Override
  public void commit() {
    Repository repository = null;
    if (parent != null) {
      repository = parent;
    }
    commitAccountCache(repository);
    commitCodeCache(repository);
    commitContractCache(repository);
    commitStorageCache(repository);
    commitStorageConsumeCache(repository);
    cpuTimeConsumeCache(repository);
  }

  @Override
  public void putAccount(Key key, Value value) {
    accountCache.put(key, value);
  }

  @Override
  public void putCode(Key key, Value value) {
    codeCache.put(key, value);
  }

  @Override
  public void putContract(Key key, Value value) {
    contractCache.put(key, value);
  }

  @Override
  public void putStorage(Key key, Storage cache) {
    storageCache.put(key, cache);
  }

  @Override
  public void putAccountValue(byte[] address, AccountCapsule accountCapsule) {
    Key key = new Key(address);
    accountCache.put(key, new Value(accountCapsule.getData(), Type.VALUE_TYPE_CREATE));
  }

  @Override
  public byte[] getBlackHoleAddress() {
    return getAccountStore().getBlackhole().getAddress().toByteArray();
  }

  @Override
  public BlockCapsule getBlockByNum(long num) {
    try {
      Sha256Hash hash = getBlockIdByNum(num);
      BlockCapsule block = this.khaosDb.getBlock(hash);
      if (block == null) {
        block = blockStore.get(hash.getBytes());
      }
      return block;
    } catch (StoreException e) {
      throw new Program.IllegalOperationException("cannot find block num");
    }
  }

  public long getHeadSlot() {
    return (getDynamicPropertiesStore().getLatestBlockHeaderTimestamp() -
        Long.parseLong(DBConfig.getGenesisBlock().getTimestamp())) / BLOCK_PRODUCED_INTERVAL;
  }

  private void commitAccountCache(Repository deposit) {
    accountCache.forEach((key, value) -> {
      if (value.getType().isCreate() || value.getType().isDirty()) {
        if (deposit != null) {
          deposit.putAccount(key, value);
        } else {
          getAccountStore().put(key.getData(), value.getAccount());
        }
      }
    });
  }

  private void commitCodeCache(Repository deposit) {
    codeCache.forEach(((key, value) -> {
      if (value.getType().isDirty() || value.getType().isCreate()) {
        if (deposit != null) {
          deposit.putCode(key, value);
        } else {
          getCodeStore().put(key.getData(), value.getCode());
        }
      }
    }));
  }

  private void commitContractCache(Repository deposit) {
    contractCache.forEach(((key, value) -> {
      if (value.getType().isDirty() || value.getType().isCreate()) {
        if (deposit != null) {
          deposit.putContract(key, value);
        } else {
          getContractStore().put(key.getData(), value.getContract());
        }
      }
    }));
  }

  private void commitStorageCache(Repository deposit) {
    storageCache.forEach((Key address, Storage storage) -> {
      if (deposit != null) {
        // write to parent cache
        deposit.putStorage(address, storage);
      } else {
        // persistence
        storage.commit();
      }
    });
  }

  private void commitStorageConsumeCache(Repository deposit) {
    storageConsumeCache.forEach((key, value) -> {
      if (value.getType().isNormal()) {
        if (deposit != null) {
          deposit.putContract(key, value);
        } else {
          getStorageUsedStore().put(key.getData(), value.getBytes());
        }
      }
    });
  }

  private void cpuTimeConsumeCache(Repository deposit) {
    storageConsumeCache.forEach((key, value) -> {
      if (value.getType().isNormal()) {
        if (deposit != null) {
          deposit.putContract(key, value);
        } else {
          getCpuTimeUsedStore().put(key.getData(), value.getBytes());
        }
      }
    });
  }

  private BlockCapsule.BlockId getBlockIdByNum(final long num) throws ItemNotFoundException {
    return this.blockIndexStore.get(num);
  }

  @Override
  public AccountCapsule createNormalAccount(byte[] address) {
    Key key = new Key(address);
    AccountCapsule account = new AccountCapsule(ByteString.copyFrom(address), AccountType.Normal,
            getDynamicPropertiesStore().getLatestBlockHeaderTimestamp());
    accountCache.put(key, new Value(account.getData(), Type.VALUE_TYPE_CREATE));
    return account;
  }

  @Override
  public void putStorageUsedValue(byte[] address, long value) {
    Key key = new Key(address);
    BytesCapsule storageUsed = storageUsedStore.get(address);
    DataWord storageUsedDataWord;
    if (storageUsed != null){
      storageUsedDataWord = new DataWord(storageUsed.getData());
    } else {
      storageUsedDataWord = DataWord.ZERO;
    }
    DataWord cpuTimeConsume = new DataWord(value + storageUsedDataWord.longValue());
    storageConsumeCache.put(key, Value.create(cpuTimeConsume.getData()));
  }

  @Override
  public void putCpuTimeUsedValue(byte[] address, long value) {
    Key key = new Key(address);
    BytesCapsule cpuTimeUsed = cpuTimeUsedStore.get(address);
    DataWord cpuTimeUsedDataWord;
    if (cpuTimeUsed != null){
      cpuTimeUsedDataWord = new DataWord(cpuTimeUsed.getData());
    } else {
      cpuTimeUsedDataWord = DataWord.ZERO;
    }
    DataWord cpuTimeConsume = new DataWord(value + cpuTimeUsedDataWord.longValue());
    cpuTimeConsumeCache.put(key, Value.create(cpuTimeConsume.getData()));
  }
}
