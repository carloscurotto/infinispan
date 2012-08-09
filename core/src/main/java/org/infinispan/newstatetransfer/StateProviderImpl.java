/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.infinispan.newstatetransfer;

import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.loaders.CacheLoaderManager;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.TransactionTable;
import org.infinispan.transaction.xa.CacheTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * // TODO [anistor] Document this
 *
 * @author anistor@redhat.com
 * @since 5.2
 */
public class StateProviderImpl implements StateProvider {

   private static final Log log = LogFactory.getLog(StateProviderImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   private Configuration configuration;
   private RpcManager rpcManager;
   private CommandsFactory commandsFactory;
   private TransactionTable transactionTable;
   private DataContainer dataContainer;
   private CacheLoaderManager cacheLoaderManager;
   private ExecutorService executorService;
   private StateTransferLock stateTransferLock;
   private long timeout;
   private int chunkSize;

   private ConsistentHash rCh;

   private final Map<Address, List<OutboundTransferTask>> transfersByDestination = new HashMap<Address, List<OutboundTransferTask>>();

   public StateProviderImpl(ExecutorService executorService,
                            Configuration configuration,
                            RpcManager rpcManager,
                            CommandsFactory commandsFactory,
                            CacheLoaderManager cacheLoaderManager,
                            DataContainer dataContainer,
                            TransactionTable transactionTable,
                            StateTransferLock stateTransferLock) {
      this.executorService = executorService;
      this.configuration = configuration;
      this.rpcManager = rpcManager;
      this.commandsFactory = commandsFactory;
      this.cacheLoaderManager = cacheLoaderManager;
      this.dataContainer = dataContainer;
      this.transactionTable = transactionTable;
      this.stateTransferLock = stateTransferLock;

      timeout = configuration.clustering().stateTransfer().timeout();

      // ignore chunk sizes <= 0
      chunkSize = configuration.clustering().stateTransfer().chunkSize();
      if (chunkSize <= 0) {
         chunkSize = Integer.MAX_VALUE;
      }
   }

   public boolean isStateTransferInProgress() {
      synchronized (transfersByDestination) {
         return !transfersByDestination.isEmpty();
      }
   }

   public void onTopologyUpdate(int topologyId, ConsistentHash rCh, ConsistentHash wCh) {
      this.rCh = rCh;

      // cancel outbound state transfers for destinations that are no longer members in new topology
      Set<Address> members = new HashSet<Address>(wCh.getMembers());
      synchronized (transfersByDestination) {
         for (Address destination : transfersByDestination.keySet()) {
            if (!members.contains(destination)) {
               List<OutboundTransferTask> transfers = transfersByDestination.remove(destination);
               for (OutboundTransferTask outboundTransfer : transfers) {
                  outboundTransfer.cancel();
               }
            }
         }
      }
   }

   public void shutdown() {
      // cancel all outbound transfers
      synchronized (transfersByDestination) {
         for (Iterator<List<OutboundTransferTask>> it = transfersByDestination.values().iterator(); it.hasNext(); ) {
            List<OutboundTransferTask> transfers = it.next();
            it.remove();
            for (OutboundTransferTask outboundTransfer : transfers) {
               outboundTransfer.cancel();
            }
         }
      }
   }

   public List<TransactionInfo> getTransactionsForSegments(Address destination, int topologyId, Set<Integer> segments) {
      if (rCh == null) {
         throw new IllegalStateException("No cache topology received yet");
      }

      Set<Integer> ownedSegments = rCh.getSegmentsForOwner(rpcManager.getAddress());
      if (!ownedSegments.containsAll(segments)) {
         segments.removeAll(ownedSegments);
         throw new IllegalArgumentException("Segments " + segments + " are not owned by " + rpcManager.getAddress());
      }

      List<TransactionInfo> transactions = new ArrayList<TransactionInfo>();
      if (configuration.transaction().transactionMode().isTransactional()) {
         // all transactions should be briefly blocked now
         stateTransferLock.transactionsExclusiveLock();
         try {
            //we migrate locks only if the cache is transactional and distributed
            collectTransactionsToTransfer(transactions, transactionTable.getRemoteTransactions(), segments);
            collectTransactionsToTransfer(transactions, transactionTable.getLocalTransactions(), segments);
            log.debugf("Found %d transactions to transfer", transactions.size());
         } finally {
            // all transactions should be unblocked now
            stateTransferLock.transactionsExclusiveUnlock();
         }
      }
      return transactions;
   }

   private void collectTransactionsToTransfer(List<TransactionInfo> transactionsToTransfer, Collection<? extends CacheTransaction> transactions, Set<Integer> segments) {
      for (CacheTransaction tx : transactions) {
         // transfer only locked keys that belong to requested segments and belong to local node
         Set<Object> lockedKeys = new HashSet<Object>();
         for (Object key : tx.getLockedKeys()) {
            if (segments.contains(rCh.getSegment(key))) {
               lockedKeys.add(key);
            }
         }
         for (Object key : tx.getBackupLockedKeys()) {
            if (segments.contains(rCh.getSegment(key))) {
               lockedKeys.add(key);
            }
         }
         List<WriteCommand> modifications = tx.getModifications();
         transactionsToTransfer.add(new TransactionInfo(tx.getGlobalTransaction(), modifications.toArray(new WriteCommand[modifications.size()]), lockedKeys));
      }
   }

   @Override
   public void startOutboundTransfer(Address destination, int topologyId, Set<Integer> segments) {
      if (trace) log.tracef("Starting outbound transfer for segments %s", segments);
      // the destination node must already have an InboundTransferTask waiting for these segments
      OutboundTransferTask outboundTransfer = new OutboundTransferTask(destination, segments, chunkSize, topologyId, rCh, this, dataContainer, cacheLoaderManager, rpcManager, commandsFactory, timeout);
      addTransfer(outboundTransfer);
      executorService.submit(outboundTransfer);
   }

   private void addTransfer(OutboundTransferTask outboundTransfer) {
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transfers = transfersByDestination.get(outboundTransfer.getDestination());
         if (transfers == null) {
            transfers = new ArrayList<OutboundTransferTask>();
            transfersByDestination.put(outboundTransfer.getDestination(), transfers);
         }
         transfers.add(outboundTransfer);
      }
   }

   @Override
   public void cancelOutboundTransfer(Address destination, int topologyId, Set<Integer> segments) {
      if (trace) log.tracef("Cancelling outbound transfer for segments %s", segments);
      // get the outbound transfers for this address and given segments and cancel the transfers
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transferTasks = transfersByDestination.get(destination);
         if (transferTasks != null) {
            for (OutboundTransferTask transferTask : transferTasks) {
               transferTask.cancelSegments(segments);
            }
         }
      }
   }

   private void removeTransfer(OutboundTransferTask transferTask) {
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transferTasks = transfersByDestination.get(transferTask.getDestination());
         if (transferTasks != null) {
            transferTasks.remove(transferTask);
            if (transferTasks.isEmpty()) {
               transfersByDestination.remove(transferTask.getDestination());
            }
         }
      }
   }

   void onTaskCompletion(OutboundTransferTask outboundTransferTask) {
      removeTransfer(outboundTransferTask);
   }
}