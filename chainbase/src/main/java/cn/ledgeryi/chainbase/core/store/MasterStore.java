package cn.ledgeryi.chainbase.core.store;

import cn.ledgeryi.chainbase.core.capsule.MasterCapsule;
import cn.ledgeryi.chainbase.core.db.LedgerYiStoreWithRevoking;
import com.google.common.collect.Streams;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@Slf4j(topic = "DB")
@Component
public class MasterStore extends LedgerYiStoreWithRevoking<MasterCapsule> {

  @Autowired
  protected MasterStore(@Value("master") String dbName) {
    super(dbName);
  }

  /**
   * get all Masteres.
   */
  public List<MasterCapsule> getAllMasteres() {
    List<MasterCapsule> masterCapsules = Streams.stream(iterator()).map(Entry::getValue).collect(Collectors.toList());
    Collections.sort(masterCapsules);
    return masterCapsules;
  }

  @Override
  public MasterCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new MasterCapsule(value);
  }
}
