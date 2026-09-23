package com.distsys.replicatedlog.master;

import java.util.concurrent.atomic.AtomicLong;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.MessageStore;


public class ReplicatedLog {
    private final MessageStore store = new MessageStore();
    private final AtomicLong nextId = new AtomicLong(1);

    public ReplicatedLog() {
        
    }

    
}
