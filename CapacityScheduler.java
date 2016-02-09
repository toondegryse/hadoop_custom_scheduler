/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator; 

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.LimitedPrivate;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.Groups;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.SchedulerResourceTypes;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.RMState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRejectedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.UpdatedContainerInfo;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.AbstractYarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Allocation;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.PreemptableResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueNotFoundException;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplication;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.NodesSensitivity;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration.QueueMapping;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration.QueueMapping.MappingType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAttemptAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAttemptRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.ContainerExpiredSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeResourceUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.security.RMContainerTokenSecretManager;
import org.apache.hadoop.yarn.server.utils.Lock;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.QueueEntitlement;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerDynamicEditException;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Queue;

@LimitedPrivate("yarn")
@Evolving
@SuppressWarnings("unchecked")
public class CapacityScheduler extends
    AbstractYarnScheduler<FiCaSchedulerApp, FiCaSchedulerNode> implements
    PreemptableResourceScheduler, CapacitySchedulerContext, Configurable {

  private static final Log LOG = LogFactory.getLog(CapacityScheduler.class);

  private String[] [] sensArr = new String [3][3];
  private CSQueue root;
  // timeout to join when we stop this service
  protected final long THREAD_JOIN_TIMEOUT_MS = 1000;

  private int f = 1;
  private int c = 2;
  private int contTeller = 0;
  //JobSubmitter js = new JobSubmitter();

  private int sensitivity = 0;
  private int firstRnd = 1;

  static final Comparator<CSQueue> queueComparator = new Comparator<CSQueue>() {
    @Override
    public int compare(CSQueue q1, CSQueue q2) {
      if (q1.getUsedCapacity() < q2.getUsedCapacity()) {
        return -1;
      } else if (q1.getUsedCapacity() > q2.getUsedCapacity()) {
        return 1;
      }
      LOG.info("Toon :: comparator");
      return q1.getQueuePath().compareTo(q2.getQueuePath());
    }
  };

  static final Comparator<FiCaSchedulerApp> applicationComparator = 
    new Comparator<FiCaSchedulerApp>() {
    @Override
    public int compare(FiCaSchedulerApp a1, FiCaSchedulerApp a2) {
      LOG.info("Toon :: compare to");
      return a1.getApplicationId().compareTo(a2.getApplicationId());
    }
  };

  @Override
  public void setConf(Configuration conf) {
      yarnConf = conf;
      //LOG.info("Toon :: clearance level from mapreduce " + conf.getInt("clearance", 0));
  }
  
  private void validateConf(Configuration conf) {
    // validate scheduler memory allocation setting
    LOG.info("Toon :: Validate conf");
    int minMem = conf.getInt(
      YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
      YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB);
    int maxMem = conf.getInt(
      YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
      YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB);

    if (minMem <= 0 || minMem > maxMem) {
      throw new YarnRuntimeException("Invalid resource scheduler memory"
        + " allocation configuration"
        + ", " + YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB
        + "=" + minMem
        + ", " + YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_MB
        + "=" + maxMem + ", min and max should be greater than 0"
        + ", max should be no smaller than min.");
    }

    // validate scheduler vcores allocation setting
    int minVcores = conf.getInt(
      YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
      YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES);
    int maxVcores = conf.getInt(
      YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
      YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES);

    if (minVcores <= 0 || minVcores > maxVcores) {
      throw new YarnRuntimeException("Invalid resource scheduler vcores"
        + " allocation configuration"
        + ", " + YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES
        + "=" + minVcores
        + ", " + YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES
        + "=" + maxVcores + ", min and max should be greater than 0"
        + ", max should be no smaller than min.");
    }
  }

  @Override
  public Configuration getConf() {
    return yarnConf;
  }

  private CapacitySchedulerConfiguration conf;
  private Configuration yarnConf;

  private Map<String, CSQueue> queues = new ConcurrentHashMap<String, CSQueue>();

  private AtomicInteger numNodeManagers = new AtomicInteger(0);

  private ResourceCalculator calculator;
  private boolean usePortForNodeName;

  private boolean scheduleAsynchronously;
  private AsyncScheduleThread asyncSchedulerThread;
  private RMNodeLabelsManager labelManager;

  public Map<NodeId, NodesSensitivity> sNodes = new HashMap<NodeId, NodesSensitivity>();
  private FiCaSchedulerNode node1;
  private FiCaSchedulerNode node2;
  private FiCaSchedulerNode node3;
  
  /**
   * EXPERT
   */
  private long asyncScheduleInterval;
  private static final String ASYNC_SCHEDULER_INTERVAL =
      CapacitySchedulerConfiguration.SCHEDULE_ASYNCHRONOUSLY_PREFIX
          + ".scheduling-interval-ms";
  private static final long DEFAULT_ASYNC_SCHEDULER_INTERVAL = 5;
  
  private boolean overrideWithQueueMappings = false;
  private List<QueueMapping> mappings = null;
  private Groups groups;

  @VisibleForTesting
  public synchronized String getMappedQueueForTest(String user)
      throws IOException {
    return getMappedQueue(user);
  }

  public CapacityScheduler() {
    super(CapacityScheduler.class.getName());
  }

  @Override
  public QueueMetrics getRootQueueMetrics() {
    return root.getMetrics();
  }

  public CSQueue getRootQueue() {
    return root;
  }
  
  @Override
  public CapacitySchedulerConfiguration getConfiguration() {
    return conf;
  }

  @Override
  public RMContainerTokenSecretManager getContainerTokenSecretManager() {
    return this.rmContext.getContainerTokenSecretManager();
  }

  @Override
  public Comparator<FiCaSchedulerApp> getApplicationComparator() {
    return applicationComparator;
  }

  @Override
  public ResourceCalculator getResourceCalculator() {
    return calculator;
  }

  @Override
  public Comparator<CSQueue> getQueueComparator() {
    return queueComparator;
  }

  @Override
  public int getNumClusterNodes() {
    return numNodeManagers.get();
  }

  @Override
  public synchronized RMContext getRMContext() {
    return this.rmContext;
  }

  @Override
  public synchronized void setRMContext(RMContext rmContext) {
    this.rmContext = rmContext;
  }

  private synchronized void initScheduler(Configuration configuration) throws
      IOException {
        LOG.info("Toon :: init scheduler");
    this.conf = loadCapacitySchedulerConfiguration(configuration);
    validateConf(this.conf);
    this.minimumAllocation = this.conf.getMinimumAllocation();
    this.maximumAllocation = this.conf.getMaximumAllocation();
    this.calculator = this.conf.getResourceCalculator();
    this.usePortForNodeName = this.conf.getUsePortForNodeName();
    this.applications =
        new ConcurrentHashMap<ApplicationId,
            SchedulerApplication<FiCaSchedulerApp>>();
    this.labelManager = rmContext.getNodeLabelManager();

    Map<String, CSQueue> newQueues = new HashMap<String, CSQueue>();

    initializeQueues(this.conf);

    scheduleAsynchronously = this.conf.getScheduleAynschronously();
    asyncScheduleInterval =
        this.conf.getLong(ASYNC_SCHEDULER_INTERVAL,
            DEFAULT_ASYNC_SCHEDULER_INTERVAL);
    if (scheduleAsynchronously) {
      asyncSchedulerThread = new AsyncScheduleThread(this);
    }

    LOG.info("Initialized CapacityScheduler with " +
        "calculator=" + getResourceCalculator().getClass() + ", " +
        "minimumAllocation=<" + getMinimumResourceCapability() + ">, " +
        "maximumAllocation=<" + getMaximumResourceCapability() + ">, " +
        "asynchronousScheduling=" + scheduleAsynchronously + ", " +
        "asyncScheduleInterval=" + asyncScheduleInterval + "ms");
  }

  private synchronized void startSchedulerThreads() {
    LOG.info("Toon :: start scheduler threads");
    if (scheduleAsynchronously) {
      Preconditions.checkNotNull(asyncSchedulerThread,
          "asyncSchedulerThread is null");
      asyncSchedulerThread.start();
    }
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    LOG.info("Toon :: service init");
    Configuration configuration = new Configuration(conf);
    initScheduler(configuration);
    super.serviceInit(conf);
  }

  @Override
  public void serviceStart() throws Exception {
        LOG.info("Toon :: service start");
    startSchedulerThreads();
    super.serviceStart();
  }

  @Override
  public void serviceStop() throws Exception {
    LOG.info("Toon :: service stop");
    synchronized (this) {
      if (scheduleAsynchronously && asyncSchedulerThread != null) {
        asyncSchedulerThread.interrupt();
        asyncSchedulerThread.join(THREAD_JOIN_TIMEOUT_MS);
      }
    }
    super.serviceStop();
  }

  @Override
  public synchronized void
  reinitialize(Configuration conf, RMContext rmContext) throws IOException {
    LOG.info("Toon :: reinitialize");
    Configuration configuration = new Configuration(conf);
    CapacitySchedulerConfiguration oldConf = this.conf;
    this.conf = loadCapacitySchedulerConfiguration(configuration);
    validateConf(this.conf);
    try {
      LOG.info("Re-initializing queues...");
      reinitializeQueues(this.conf);
    } catch (Throwable t) {
      this.conf = oldConf;
      throw new IOException("Failed to re-init queues", t);
    }
  }
  
  long getAsyncScheduleInterval() {
    LOG.info("Toon :: this is the async schedule interval");
    return asyncScheduleInterval;
  }

  private final static Random random = new Random(System.currentTimeMillis());
  
  /**
   * Schedule on all nodes by starting at a random point.
   * @param cs
   */
  static void schedule(CapacityScheduler cs) {
    // First randomize the start point
    int fSens = 2;
    int current = 0;
    Collection<FiCaSchedulerNode> nodes = cs.getAllNodes().values();


     
      int start = random.nextInt(nodes.size());
      LOG.info("Toon :: schedule node size " + nodes.size());
      for (FiCaSchedulerNode node : nodes) {
        LOG.info("Toon :: each node " + node);
        if (current++ >= start) {
          LOG.info("Toon :: These is the node being added " + node);
          cs.allocateContainersToNode(node);
        }
      }
      // Now, just get everyone to be safe
      for (FiCaSchedulerNode node : nodes) {
        cs.allocateContainersToNode(node);
        LOG.info("Toon :: this is the FicaSchedulerNode " + node);
      }
      try {
        Thread.sleep(cs.getAsyncScheduleInterval());
      } catch (InterruptedException e) {}
  }
  
  static class AsyncScheduleThread extends Thread {

    private final CapacityScheduler cs;
    private AtomicBoolean runSchedules = new AtomicBoolean(false);

    public AsyncScheduleThread(CapacityScheduler cs) {
      LOG.info("Toon :: async schedule thread");
      this.cs = cs;
      setDaemon(true);
    }

    @Override
    public void run() {
      LOG.info("Toon :: run");
      while (true) {
        if (!runSchedules.get()) {
          try {
            Thread.sleep(100);
          } catch (InterruptedException ie) {}
        } else {
          schedule(cs);
        }
      }
    }

    public void beginSchedule() {
      LOG.info("Toon :: begin schedule");
      runSchedules.set(true);
    }

    public void suspendSchedule() {
      LOG.info("Toon :: suspend schedule");
      runSchedules.set(false);
    }

  }
  
  @Private
  public static final String ROOT_QUEUE = 
    CapacitySchedulerConfiguration.PREFIX + CapacitySchedulerConfiguration.ROOT;

  static class QueueHook {
    public CSQueue hook(CSQueue queue) {
      return queue;
    }
  }
  private static final QueueHook noop = new QueueHook();

  private void initializeQueueMappings() throws IOException {
    //LOG.info("Toon :: initialize queue mappings"); // shows
    overrideWithQueueMappings = conf.getOverrideWithQueueMappings();
    LOG.info("Initialized queue mappings, override: "
        + overrideWithQueueMappings);
    // Get new user/group mappings
    List<QueueMapping> newMappings = conf.getQueueMappings();
    //check if mappings refer to valid queues
    for (QueueMapping mapping : newMappings) {
      //LOG.info("Toon :: mapping " + mapping.queue.getQueueInfo());
      LOG.info("Toon :: mapping to string ");
      if (!mapping.queue.equals(CURRENT_USER_MAPPING) &&
          !mapping.queue.equals(PRIMARY_GROUP_MAPPING)) {
        CSQueue queue = queues.get(mapping.queue);
        if (queue == null || !(queue instanceof LeafQueue)) {
          throw new IOException(
              "mapping contains invalid or non-leaf queue " + mapping.queue);
        }
      }
    }
    //apply the new mappings since they are valid
    mappings = newMappings;
    LOG.info("Toon :: mappings " + mappings); // shows []
    LOG.info("Toon :: mapping size " + mappings.size()); // shows size 0
    // initialize groups if mappings are present
    if (mappings.size() > 0) {
      groups = new Groups(conf);
    }
  }

  @Lock(CapacityScheduler.class)
  private void initializeQueues(CapacitySchedulerConfiguration conf)
    throws IOException {
      LOG.info("Toon :: initialize queues");
    root = 
        parseQueue(this, conf, null, CapacitySchedulerConfiguration.ROOT, 
            queues, queues, noop);

    // **********
    // ********** add more queues here?
    // **********

    labelManager.reinitializeQueueLabels(getQueueToLabels());
    LOG.info("Initialized root queue " + root); // shows
    LOG.info("FARANGTOONE " + queues); // shows root queue
    initializeQueueMappings();
  }

  @Lock(CapacityScheduler.class)
  private void reinitializeQueues(CapacitySchedulerConfiguration conf) 
  throws IOException {
    LOG.info("Toon :: reinitialize queues");
    // Parse new queues
    Map<String, CSQueue> newQueues = new HashMap<String, CSQueue>();
    CSQueue newRoot = 
        parseQueue(this, conf, null, CapacitySchedulerConfiguration.ROOT, 
            newQueues, queues, noop); 
    
    // Ensure all existing queues are still present
    validateExistingQueues(queues, newQueues);

    // Add new queues
    addNewQueues(queues, newQueues);
    
    // Re-configure queues
    root.reinitialize(newRoot, clusterResource);
    initializeQueueMappings();

    // Re-calculate headroom for active applications
    root.updateClusterResource(clusterResource);

    labelManager.reinitializeQueueLabels(getQueueToLabels());
  }
  
  private Map<String, Set<String>> getQueueToLabels() {
    LOG.info("Toon :: get queue to labels");
    Map<String, Set<String>> queueToLabels = new HashMap<String, Set<String>>();
    for (CSQueue queue : queues.values()) {
      // to test
      LOG.info("Toon :: cs queue value in for loop " + queue.getQueueName()); // shows root
      LOG.info("Toon :: cs queue get accessible node labels " + queue.getAccessibleNodeLabels()); // shows [*]
      //LOG.info("Toon :: get this value from mapreduce " + JobSubmissionFiles.JOB_FILE_PERMISSION);
      queueToLabels.put(queue.getQueueName(), queue.getAccessibleNodeLabels());
    }
    return queueToLabels;
  }

  /**
   * Ensure all existing queues are present. Queues cannot be deleted
   * @param queues existing queues
   * @param newQueues new queues
   */
  @Lock(CapacityScheduler.class)
  private void validateExistingQueues(
      Map<String, CSQueue> queues, Map<String, CSQueue> newQueues) 
  throws IOException {
    LOG.info("Toon :: validate existing queues");
    //LOG.info("Toon :: queues key " + queues.getKey());
    //LOG.info("Toon :: queues value " + queues.getValue());
    // check that all static queues are included in the newQueues list
    for (Map.Entry<String, CSQueue> e : queues.entrySet()) {
      LOG.info("Toon :: for cs queue entry key " + e.getKey());
      LOG.info("Toon :: for cs queue entry value " + e.getValue());
      if (!(e.getValue() instanceof ReservationQueue)) {
        if (!newQueues.containsKey(e.getKey())) {
          throw new IOException(e.getKey() + " cannot be found during refresh!");
        }
      }
    }
  }

  /**
   * Add the new queues (only) to our list of queues...
   * ... be careful, do not overwrite existing queues.
   * @param queues
   * @param newQueues
   */
  @Lock(CapacityScheduler.class)
  private void addNewQueues(
      Map<String, CSQueue> queues, Map<String, CSQueue> newQueues) 
  {
    LOG.info("Toon :: add new queues");
    for (Map.Entry<String, CSQueue> e : newQueues.entrySet()) {
      String queueName = e.getKey();
      CSQueue queue = e.getValue();
      if (!queues.containsKey(queueName)) {
        queues.put(queueName, queue);
      }
    }
  }
  
  @Lock(CapacityScheduler.class)
  static CSQueue parseQueue(
      CapacitySchedulerContext csContext,
      CapacitySchedulerConfiguration conf, 
      CSQueue parent, String queueName, Map<String, CSQueue> queues,
      Map<String, CSQueue> oldQueues, 
      QueueHook hook) throws IOException {
    CSQueue queue;
    String fullQueueName =
        (parent == null) ? queueName
            : (parent.getQueuePath() + "." + queueName);
    String[] childQueueNames = 
      conf.getQueues(fullQueueName);
    boolean isReservableQueue = conf.isReservable(fullQueueName);
    if (childQueueNames == null || childQueueNames.length == 0) {
      LOG.info("Toon :: parse queue");
      if (null == parent) {
        throw new IllegalStateException(
            "Queue configuration missing child queue names for " + queueName);
      }
      // Check if the queue will be dynamically managed by the Reservation
      // system
      if (isReservableQueue) {
        LOG.info("Toon :: if is reservable queue"); // no show
        queue =
            new PlanQueue(csContext, queueName, parent,
                oldQueues.get(queueName));
      } else {
        queue =
            new LeafQueue(csContext, queueName, parent,
                oldQueues.get(queueName));

        // Used only for unit tests
        queue = hook.hook(queue);
      }
    } else {
      LOG.info("Toon :: else if is reservable queue");
      if (isReservableQueue) {
        throw new IllegalStateException(
            "Only Leaf Queues can be reservable for " + queueName);
      }
      ParentQueue parentQueue = 
        new ParentQueue(csContext, queueName, parent, oldQueues.get(queueName));

      // Used only for unit tests
      queue = hook.hook(parentQueue);

      List<CSQueue> childQueues = new ArrayList<CSQueue>();
      for (String childQueueName : childQueueNames) {
        CSQueue childQueue = 
          parseQueue(csContext, conf, queue, childQueueName, 
              queues, oldQueues, hook);
        childQueues.add(childQueue);
      }

      // create 3 queues instead off for loop above

      parentQueue.setChildQueues(childQueues);
    }

    if(queue instanceof LeafQueue == true && queues.containsKey(queueName)
      && queues.get(queueName) instanceof LeafQueue == true) {
      throw new IOException("Two leaf queues were named " + queueName
        + ". Leaf queue names must be distinct");
    }
    queues.put(queueName, queue);

    LOG.info("Initialized queue: " + queue);
    return queue;
  }

  public synchronized CSQueue getQueue(String queueName) {
    if (queueName == null) {
      return null;
    }
    return queues.get(queueName);
  }

  private static final String CURRENT_USER_MAPPING = "%user";

  private static final String PRIMARY_GROUP_MAPPING = "%primary_group";

  private String getMappedQueue(String user) throws IOException {
    LOG.info("Toon :: get mapped queue");
    for (QueueMapping mapping : mappings) {
      if (mapping.type == MappingType.USER) {
        if (mapping.source.equals(CURRENT_USER_MAPPING)) {
          if (mapping.queue.equals(CURRENT_USER_MAPPING)) {
            return user;
          }
          else if (mapping.queue.equals(PRIMARY_GROUP_MAPPING)) {
            return groups.getGroups(user).get(0);
          }
          else {
            return mapping.queue;
          }
        }
        if (user.equals(mapping.source)) {
          return mapping.queue;
        }
      }
      if (mapping.type == MappingType.GROUP) {
        for (String userGroups : groups.getGroups(user)) {
          if (userGroups.equals(mapping.source)) {
            return mapping.queue;
          }
        }
      }
    }
    return null;
  }

  private synchronized void addApplication(ApplicationId applicationId,
    String queueName, String user, boolean isAppRecovering) {
    LOG.info("Toon :: add application by " + user);
    LOG.info("Toon :: add application queue name " + queueName);
    if (mappings != null && mappings.size() > 0) {
      try {
        String mappedQueue = getMappedQueue(user);
        if (mappedQueue != null) {
          // We have a mapping, should we use it?
          if (queueName.equals(YarnConfiguration.DEFAULT_QUEUE_NAME)
              || overrideWithQueueMappings) {
            LOG.info("Application " + applicationId + " user " + user
                + " mapping [" + queueName + "] to [" + mappedQueue
                + "] override " + overrideWithQueueMappings);
            queueName = mappedQueue;
            // Toon queue submission is here?
            RMApp rmApp = rmContext.getRMApps().get(applicationId);
            rmApp.setQueue(queueName);
          }
        }
      } catch (IOException ioex) {
        String message = "Failed to submit application " + applicationId +
            " submitted by user " + user + " reason: " + ioex.getMessage();
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMAppRejectedEvent(applicationId, message));
        return;
      }
    }

    // sanity checks.
    CSQueue queue = getQueue(queueName);
    if (queue == null) {
      //During a restart, this indicates a queue was removed, which is
      //not presently supported
      if (isAppRecovering) {
        String queueErrorMsg = "Queue named " + queueName
           + " missing during application recovery."
           + " Queue removal during recovery is not presently supported by the"
           + " capacity scheduler, please restart with all queues configured"
           + " which were present before shutdown/restart.";
        LOG.fatal(queueErrorMsg);
        throw new QueueNotFoundException(queueErrorMsg);
      }
      String message = "Application " + applicationId + 
      " submitted by user " + user + " to unknown queue: " + queueName;
      this.rmContext.getDispatcher().getEventHandler()
          .handle(new RMAppRejectedEvent(applicationId, message));
      return;
    }
    if (!(queue instanceof LeafQueue)) {
      String message = "Application " + applicationId + 
          " submitted by user " + user + " to non-leaf queue: " + queueName;
      this.rmContext.getDispatcher().getEventHandler()
          .handle(new RMAppRejectedEvent(applicationId, message));
      return;
    }
    // Submit to the queue
    try {
      queue.submitApplication(applicationId, user, queueName);
    } catch (AccessControlException ace) {
      LOG.info("Failed to submit application " + applicationId + " to queue "
          + queueName + " from user " + user, ace);
      this.rmContext.getDispatcher().getEventHandler()
          .handle(new RMAppRejectedEvent(applicationId, ace.toString()));
      return;
    }
    // update the metrics
    queue.getMetrics().submitApp(user);
    SchedulerApplication<FiCaSchedulerApp> application =
        new SchedulerApplication<FiCaSchedulerApp>(queue, user);
    applications.put(applicationId, application);
    LOG.info("Accepted application " + applicationId + " from user: " + user
        + ", in queue: " + queueName);
    if (isAppRecovering) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(applicationId + " is recovering. Skip notifying APP_ACCEPTED");
      }
    } else {
      rmContext.getDispatcher().getEventHandler()
        .handle(new RMAppEvent(applicationId, RMAppEventType.APP_ACCEPTED));
    }
  }

  private synchronized void addApplicationAttempt(
      ApplicationAttemptId applicationAttemptId,
      boolean transferStateFromPreviousAttempt,
      boolean isAttemptRecovering) {
    LOG.info("Toon :: add application attempt");
    SchedulerApplication<FiCaSchedulerApp> application =
        applications.get(applicationAttemptId.getApplicationId());
    CSQueue queue = (CSQueue) application.getQueue();

    FiCaSchedulerApp attempt =
        new FiCaSchedulerApp(applicationAttemptId, application.getUser(),
          queue, queue.getActiveUsersManager(), rmContext);
    if (transferStateFromPreviousAttempt) {
      attempt.transferStateFromPreviousAttempt(application
        .getCurrentAppAttempt());
    }
    application.setCurrentAppAttempt(attempt);

    queue.submitApplicationAttempt(attempt, application.getUser());
    LOG.info("Added Application Attempt " + applicationAttemptId
        + " to scheduler from user " + application.getUser() + " in queue "
        + queue.getQueueName());
    if (isAttemptRecovering) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(applicationAttemptId
            + " is recovering. Skipping notifying ATTEMPT_ADDED");
      }
    } else {
      rmContext.getDispatcher().getEventHandler().handle(
        new RMAppAttemptEvent(applicationAttemptId,
            RMAppAttemptEventType.ATTEMPT_ADDED));
    }
  }

  private synchronized void doneApplication(ApplicationId applicationId,
      RMAppState finalState) {
    LOG.info("Toon :: done application");
    SchedulerApplication<FiCaSchedulerApp> application =
        applications.get(applicationId);
    if (application == null){
      // The AppRemovedSchedulerEvent maybe sent on recovery for completed apps,
      // ignore it.
      LOG.warn("Couldn't find application " + applicationId);
      return;
    }
    CSQueue queue = (CSQueue) application.getQueue();
    if (!(queue instanceof LeafQueue)) {
      LOG.error("Cannot finish application " + "from non-leaf queue: "
          + queue.getQueueName());
    } else {
      queue.finishApplication(applicationId, application.getUser());
    }
    application.stop(finalState);
    applications.remove(applicationId);
  }

  private synchronized void doneApplicationAttempt(
      ApplicationAttemptId applicationAttemptId,
      RMAppAttemptState rmAppAttemptFinalState, boolean keepContainers) {
    LOG.info("Application Attempt " + applicationAttemptId + " is done." +
        " finalState=" + rmAppAttemptFinalState);
    LOG.info("Toon :: done application attempt");
    FiCaSchedulerApp attempt = getApplicationAttempt(applicationAttemptId);
    SchedulerApplication<FiCaSchedulerApp> application =
        applications.get(applicationAttemptId.getApplicationId());

    if (application == null || attempt == null) {
      LOG.info("Unknown application " + applicationAttemptId + " has completed!");
      return;
    }

    // Release all the allocated, acquired, running containers
    for (RMContainer rmContainer : attempt.getLiveContainers()) {
      if (keepContainers
          && rmContainer.getState().equals(RMContainerState.RUNNING)) {
        // do not kill the running container in the case of work-preserving AM
        // restart.
        LOG.info("Skip killing " + rmContainer.getContainerId());
        continue;
      }
      completedContainer(
        rmContainer,
        SchedulerUtils.createAbnormalContainerStatus(
          rmContainer.getContainerId(), SchedulerUtils.COMPLETED_APPLICATION),
        RMContainerEventType.KILL);
    }

    // Release all reserved containers
    for (RMContainer rmContainer : attempt.getReservedContainers()) {
      completedContainer(
        rmContainer,
        SchedulerUtils.createAbnormalContainerStatus(
          rmContainer.getContainerId(), "Application Complete"),
        RMContainerEventType.KILL);
    }

    // Clean up pending requests, metrics etc.
    attempt.stop(rmAppAttemptFinalState);

    // Inform the queue
    String queueName = attempt.getQueue().getQueueName();
    CSQueue queue = queues.get(queueName);
    if (!(queue instanceof LeafQueue)) {
      LOG.error("Cannot finish application " + "from non-leaf queue: "
          + queueName);
    } else {
      queue.finishApplicationAttempt(attempt, queue.getQueueName());
    }
  }

  @Override
  @Lock(Lock.NoLock.class)
  public Allocation allocate(ApplicationAttemptId applicationAttemptId,
      List<ResourceRequest> ask, List<ContainerId> release, 
      List<String> blacklistAdditions, List<String> blacklistRemovals) {
    LOG.info("Toon :: allocate");

    FiCaSchedulerApp application = getApplicationAttempt(applicationAttemptId);
    if (application == null) {
      LOG.info("Calling allocate on removed " +
          "or non existant application " + applicationAttemptId);
      return EMPTY_ALLOCATION;
    }
    
    // Sanity check
    SchedulerUtils.normalizeRequests(
        ask, getResourceCalculator(), getClusterResource(),
        getMinimumResourceCapability(), maximumAllocation);

    // Release containers
    releaseContainers(release, application);

    synchronized (application) {

      // make sure we aren't stopping/removing the application
      // when the allocate comes in
      if (application.isStopped()) {
        LOG.info("Calling allocate on a stopped " +
            "application " + applicationAttemptId);
        return EMPTY_ALLOCATION;
      }

      if (!ask.isEmpty()) {

        if(LOG.isDebugEnabled()) {
          LOG.debug("allocate: pre-update" +
            " applicationAttemptId=" + applicationAttemptId + 
            " application=" + application);
        }
        application.showRequests();
  
        // Update application requests
        application.updateResourceRequests(ask);
  
        LOG.debug("allocate: post-update");
        application.showRequests();
      }

      if(LOG.isDebugEnabled()) {
        LOG.debug("allocate:" +
          " applicationAttemptId=" + applicationAttemptId + 
          " #ask=" + ask.size());
      }

      application.updateBlacklist(blacklistAdditions, blacklistRemovals);

      return application.getAllocation(getResourceCalculator(),
                   clusterResource, getMinimumResourceCapability());
    }
  }

  @Override
  @Lock(Lock.NoLock.class)
  public QueueInfo getQueueInfo(String queueName, 
      boolean includeChildQueues, boolean recursive) 
  throws IOException {
    CSQueue queue = null;
    LOG.info("Toon :: get queue info");
    synchronized (this) {
      queue = this.queues.get(queueName); 
    }

    if (queue == null) {
      throw new IOException("Unknown queue: " + queueName);
    }
    return queue.getQueueInfo(includeChildQueues, recursive);
  }

  @Override
  @Lock(Lock.NoLock.class)
  public List<QueueUserACLInfo> getQueueUserAclInfo() {
    LOG.info("Toon :: get queue user acl info");
    UserGroupInformation user = null;
    try {
      user = UserGroupInformation.getCurrentUser();
    } catch (IOException ioe) {
      // should never happen
      return new ArrayList<QueueUserACLInfo>();
    }

    return root.getQueueUserAclInfo(user);
  }

  private synchronized void nodeUpdate(RMNode nm) {
    LOG.info("Toon :: node update " + nm.getHostName());

    if (LOG.isDebugEnabled()) {
      LOG.debug("nodeUpdate: " + nm + " clusterResources: " + clusterResource);
    }
    /*if(sensitivity == 1){
      FiCaSchedulerNode node = getNode("Hadoop100");
    }else if(sensitivity == 2){
      FiCaSchedulerNode node = getNode("Hadoop101");
    }else{
      FiCaSchedulerNode node = getNode(nm.getNodeID());
    }*/
    // loop through sNodes and get the appropriate node

    // node.getAvailableResource()
    FiCaSchedulerNode node = getNode(nm.getNodeID());
    /*LOG.info("Toon :: the sensitivity is " + node.getSensitivity());
    if(node.getSensitivity() == 1){
      // get a new node
      LOG.info("Toon :: the sensitivity is set to " + node.getSensitivity());
    }else{
      LOG.info("Toon :: the sensitivity is set to nothing");
    }*/
    /*Set keys = nodes.keySet();
    for (Iterator i = keys.iterator(); i.hasNext();) {
      RMNode key = (RMNode) i.next();
      FiCaSchedulerNode value = sNodes.get(key);
      // get the sNodes node to check the sensitivity
      LOG.info("Toon :: the node is " + value.toString()); // doesnt show at all!!
    }*/
    

    //LOG.info("Toon :: i want to get Hadoop 100 " + nodes.get(nm.getNodeID()));
    
    List<UpdatedContainerInfo> containerInfoList = nm.pullContainerUpdates();
    List<ContainerStatus> newlyLaunchedContainers = new ArrayList<ContainerStatus>();
    List<ContainerStatus> completedContainers = new ArrayList<ContainerStatus>();
    
    // Toon added the if nm.getHostName
    //if(nm.getHostName() == "Hadoop101"){
      for(UpdatedContainerInfo containerInfo : containerInfoList) {
        newlyLaunchedContainers.addAll(containerInfo.getNewlyLaunchedContainers());
        completedContainers.addAll(containerInfo.getCompletedContainers());
        LOG.info("Toon :: updated container info " + node); // shows
      }
      
      // Processing the newly launched containers
      for (ContainerStatus launchedContainer : newlyLaunchedContainers) {
        containerLaunchedOnNode(launchedContainer.getContainerId(), node);
        LOG.info("Toon :: container status " + node); // shows
      }

      // Process completed containers
      for (ContainerStatus completedContainer : completedContainers) {
        ContainerId containerId = completedContainer.getContainerId();
        LOG.info("Toon :: completed container info " + node + " these are containers " + containerId); // shows up
        LOG.debug("Container FINISHED: " + containerId);
        completedContainer(getRMContainer(containerId), 
            completedContainer, RMContainerEventType.FINISHED);
      }
    //}

    // Now node data structures are upto date and ready for scheduling.
    if(LOG.isDebugEnabled()) {
      LOG.debug("Node being looked for scheduling " + nm
        + " availableResource: " + node.getAvailableResource());
    }
  }
  
  /**
   * Process resource update on a node.
   */
  private synchronized void updateNodeAndQueueResource(RMNode nm, 
      ResourceOption resourceOption) {
    LOG.info("Toon :: update node and queue resource");
    updateNodeResource(nm, resourceOption);
    root.updateClusterResource(clusterResource);
  }

  private synchronized void allocateContainersToNode(FiCaSchedulerNode node) {
    
    if (rmContext.isWorkPreservingRecoveryEnabled()
        && !rmContext.isSchedulerReadyForAllocatingContainers()) {
      return;
    }

    LOG.info("Toon :: allocate Containers To Node " + node.getNodeID());

    // Assign new containers...
    // 1. Check for reserved applications
    // 2. Schedule if there are no reservations

    RMContainer reservedContainer = node.getReservedContainer();
    // LOG.info("Toon :: is the container reserved? " + reservedContainer); // returns null
    if (reservedContainer != null) {
      FiCaSchedulerApp reservedApplication =
          getCurrentAttemptForContainer(reservedContainer.getContainerId());
      
      // Try to fulfill the reservation
      LOG.info("Trying to fulfill reservation for application " + 
          reservedApplication.getApplicationId() + " on node: " + 
          node.getNodeID());
       
      LeafQueue queue = ((LeafQueue)reservedApplication.getQueue());
      LOG.info("Toon :: queue reserved application " + queue);
      //LOG.info("Toon :: queue assign containers " + queue.getApplication());
      //LOG.info("Toon :: queue get value" + queue.getValue());
      //LOG.info("Toon :: queue " + queue.getQueueInfo());
      LOG.info("Toon :: " + queue.getQueuePath());

      CSAssignment assignment = queue.assignContainers(clusterResource, node,
          false);

      LOG.info("Toon :: CS assignment " + assignment);
      
      RMContainer excessReservation = assignment.getExcessReservation();
      if (excessReservation != null) {
      Container container = excessReservation.getContainer();
      LOG.info("Toon :: excess reservation get container " + container);
      queue.completedContainer(
          clusterResource, assignment.getApplication(), node, 
          excessReservation, 
          SchedulerUtils.createAbnormalContainerStatus(
              container.getId(), 
              SchedulerUtils.UNRESERVED_CONTAINER), 
          RMContainerEventType.RELEASED, null, true);
      }

    }
    
    // Try to schedule more if there are no reservations to fulfill
    if (node.getReservedContainer() == null) {
      // if this is the wrong node sensitivity wise, can i get another one?
      //LOG.info("Toon :: here reserved container is false ");
      if (calculator.computeAvailableContainers(node.getAvailableResource(),
        minimumAllocation) > 0) {
        //LOG.info("Toon :: Trying to schedule on node: " + node.getNodeName() +
        //      ", available: " + node.getAvailableResource() + " and the clusteresource is this " + clusterResource); // shows
        if (LOG.isDebugEnabled()) {
          LOG.debug("Trying to schedule on node: " + node.getNodeName() +
              ", available: " + node.getAvailableResource());
        }

        // ********
        // ******** clearance 0 assign containers
        // ********
        // ******** clearance 1 assign containers
        // ********

        root.assignContainers(clusterResource, node, false);
        LOG.info("Toon :: is this root the queue ?? " + root);

        LOG.info("Toon :: assign the item from the queue to this node " + node.getNodeID());
      }
    } else {
      LOG.info("Skipping scheduling since node " + node.getNodeID() + 
          " is reserved by application " + 
          node.getReservedContainer().getContainerId().getApplicationAttemptId()
          );
    }
  
  }

  public synchronized void allocateContainersToNodeSafely(){
    if(firstRnd == 1){
      firstRnd = 0;
      RMContainer reservedContainer1 = node1.getReservedContainer();
      RMContainer reservedContainer2 = node2.getReservedContainer();
      RMContainer reservedContainer3 = node3.getReservedContainer();
      //if ((reservedContainer1 != null) || (reservedContainer2 != null) || (reservedContainer3 != null)){
        if(sensitivity == 2){
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node1);           
          root.assignContainers(clusterResource, node1, false);
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node2);           
          root.assignContainers(clusterResource, node2, false);
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node3);           
          root.assignContainers(clusterResource, node3, false);
        }else if(sensitivity ==1){
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node1);           
          root.assignContainers(clusterResource, node1, false);
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node2);           
          root.assignContainers(clusterResource, node2, false);
        }else{
          LOG.info("Toon :: assign containers on cloud with sensitivity less or equal to 2! " + node1);           
          root.assignContainers(clusterResource, node1, false);
        }
      //}
    }
  }

  @Override
  public void handle(SchedulerEvent event) {
    LOG.info("Toon :: handle" + event.getType());
    switch(event.getType()) {
    case NODE_ADDED:
    {
      NodeAddedSchedulerEvent nodeAddedEvent = (NodeAddedSchedulerEvent)event;
      addNode(nodeAddedEvent.getAddedRMNode());
      recoverContainersOnNode(nodeAddedEvent.getContainerReports(),
        nodeAddedEvent.getAddedRMNode());
    }
    break;
    case NODE_REMOVED:
    {
      NodeRemovedSchedulerEvent nodeRemovedEvent = (NodeRemovedSchedulerEvent)event;
      removeNode(nodeRemovedEvent.getRemovedRMNode());
    }
    break;
    case NODE_RESOURCE_UPDATE:
    {
      NodeResourceUpdateSchedulerEvent nodeResourceUpdatedEvent = 
          (NodeResourceUpdateSchedulerEvent)event;
      updateNodeAndQueueResource(nodeResourceUpdatedEvent.getRMNode(),
        nodeResourceUpdatedEvent.getResourceOption());
    }
    break;
    case NODE_UPDATE:
    {
      NodeUpdateSchedulerEvent nodeUpdatedEvent = (NodeUpdateSchedulerEvent)event;
      RMNode node = nodeUpdatedEvent.getRMNode();
      nodeUpdate(node);

      // if node getSensitivity <= sens; process node, else nothing
      /*for (Map.Entry<NodeId, NodesSensitivity> entry : sNodes.entrySet()) {
        NodeId key = entry.getKey();
          FiCaSchedulerNode value = (FiCaSchedulerNode) entry.getValue();
          //LOG.info("Toon :: this is the value and also the node to check sensitivity off " + entry.getHostName());
          String hnm = key.getHost().toString(); 
          NodesSensitivity val = entry.getValue(); 
          int snst = val.getSensitivity();
          LOG.info("Toon :: node getHostName " + node.getHostName());
          LOG.info("Toon :: node " + node);
          //LOG.info("Toon :: value getHostName " + value.getHostName());
          LOG.info("Toon :: value getHost " + value.getNodeName());
          //LOG.info("Toon :: val getHostName " + val.getHostName());
          //LOG.info("Toon :: val getHost " + val.getHost());

          //LOG.info("Toon :: sensitivity node " + value.getSensitivity());
          LOG.info("Toon :: sensitivity node  " + val.getSensitivity());
      }

      if (!scheduleAsynchronously) {            
        Iterator it = sNodes.keySet().iterator();
        while(it.hasNext()){
          NodesSensitivity next = sNodes.get(it.next());
          if(next.getSensitivity() <= sensitivity){
            LOG.info("Toon :: Allocate this shit! " + next);
            allocateContainersToNode(next);
          }
        }*/

        for (Map.Entry<NodeId, NodesSensitivity> entry : sNodes.entrySet()) {
          NodeId key = entry.getKey();
          NodesSensitivity value = entry.getValue();
          //LOG.info("Toon :: key hostname: " + key.getHost());
          //LOG.info("Toon :: n gethostname: " + node.getHostName());
          if(key.getHost().equals(node.getHostName())){
            if(value.getSensitivity() <= sensitivity){
              LOG.info("Toon :: allocate this node " + value.getNodeName());
              allocateContainersToNode(value);
            }else{
              LOG.info("Toon :: allocate to node 1 " + node1.getNodeName());
              allocateContainersToNode(node1);
            }
          }
        }

        /*for (Map.Entry<NodeId, NodesSensitivity> entry : sNodes.entrySet()) {
          NodeId key = entry.getKey();
          NodesSensitivity value = entry.getValue();
          if(key.getHost().equals(node.getHostName())){
            if(value.getSensitivity()<= sensitivity && contTeller <= 2){
              LOG.info("Toon :: conTeller <= 2; this is a node to schedule to : " + node3);
              LOG.info("Toon :: this is the prev node " + value);
              allocateContainersToNode(node3);
            }else if(value.getSensitivity() <= sensitivity && contTeller <= 5){
              LOG.info("Toon :: conTeller <= 5; this is a node to schedule to : " + node3);
              LOG.info("Toon :: this is the prev node " + value);
              allocateContainersToNode(node2);
            }else{
              LOG.info("Toon :: conTeller else; this is a node to schedule to : " + node3);
              LOG.info("Toon :: this is the prev node " + value);
              allocateContainersToNode(node1);
            }
          }
          contTeller = contTeller + 1;
        }*/

        /*for (Map.Entry<NodeId, NodesSensitivity> entry : sNodes.entrySet()) {
          NodeId key = entry.getKey();
          FiCaSchedulerNode value = (FiCaSchedulerNode) entry.getValue();
          //LOG.info("Toon :: this is the value and also the node to check sensitivity off " + entry.getHostName());
          String hnm = key.getHost().toString(); 
          NodesSensitivity val = entry.getValue(); 
          int snst = val.getSensitivity();
          LOG.info("Toon :: this is the retrieved sensitivity "+snst);
          LOG.info("Toon :: Key " + key);
          LOG.info("Toon :: EQUAL value get node name " + value.getNodeName()); 
          LOG.info("Toon :: EQUAL node get host name " + hnm);
          //if(value.getNodeName() == hnm){
            //LOG.info("Toon :: this key and node are the same " + key + " : " + value);
            if(snst == sensitivity){
              LOG.info("Toon :: ALLOCATE THIS SHIT! snst 2 " + getNode(node.getNodeID()));           
              allocateContainersToNode(getNode(node.getNodeID()));
            }else if(snst == sensitivity){
              LOG.info("Toon :: ALLOCATE THIS SHIT! snst 1" + getNode(node.getNodeID()));           
              allocateContainersToNode(getNode(node.getNodeID()));
            }else if(snst == sensitivity){
              LOG.info("Toon :: ALLOCATE THIS SHIT! snst 0" + getNode(node.getNodeID()));           
              allocateContainersToNode(getNode(node.getNodeID()));
            }else{
              LOG.info("Toon :: no allocations to be done");
            }

              //allocateContainersToNodeSafely();

         // }
          //allocateContainersToNode(getNode(node1.getNodeID()));
          LOG.info("Toon :: Value " + value);
          LOG.info("Toon :: Test this key: " + hnm);
        }*/
      //}
    }
    break;
    case APP_ADDED:
    {
      AppAddedSchedulerEvent appAddedEvent = (AppAddedSchedulerEvent) event;
      String queueName =
          resolveReservationQueueName(appAddedEvent.getQueue(),
              appAddedEvent.getApplicationId(),
              appAddedEvent.getReservationID());
      // get available nodes to run on
      if (queueName != null) {
        addApplication(appAddedEvent.getApplicationId(),
            queueName,
            appAddedEvent.getUser(),
            appAddedEvent.getIsAppRecovering());
      }
    }
    break;
    case APP_REMOVED:
    {
      AppRemovedSchedulerEvent appRemovedEvent = (AppRemovedSchedulerEvent)event;
      doneApplication(appRemovedEvent.getApplicationID(),
        appRemovedEvent.getFinalState());
    }
    break;
    case APP_ATTEMPT_ADDED:
    {
      AppAttemptAddedSchedulerEvent appAttemptAddedEvent =
          (AppAttemptAddedSchedulerEvent) event;
      addApplicationAttempt(appAttemptAddedEvent.getApplicationAttemptId(),
        appAttemptAddedEvent.getTransferStateFromPreviousAttempt(),
        appAttemptAddedEvent.getIsAttemptRecovering());
    }
    break;
    case APP_ATTEMPT_REMOVED:
    {
      AppAttemptRemovedSchedulerEvent appAttemptRemovedEvent =
          (AppAttemptRemovedSchedulerEvent) event;
      doneApplicationAttempt(appAttemptRemovedEvent.getApplicationAttemptID(),
        appAttemptRemovedEvent.getFinalAttemptState(),
        appAttemptRemovedEvent.getKeepContainersAcrossAppAttempts());
    }
    break;
    case CONTAINER_EXPIRED:
    {
      ContainerExpiredSchedulerEvent containerExpiredEvent = 
          (ContainerExpiredSchedulerEvent) event;
      ContainerId containerId = containerExpiredEvent.getContainerId();
      completedContainer(getRMContainer(containerId), 
          SchedulerUtils.createAbnormalContainerStatus(
              containerId, 
              SchedulerUtils.EXPIRED_CONTAINER), 
          RMContainerEventType.EXPIRE);
    }
    break;
    default:
      LOG.error("Invalid eventtype " + event.getType() + ". Ignoring!");
    }
  }

  private synchronized void addNode(RMNode nodeManager) {
    LOG.info("Toon :: add node" + this.nodes + " add one node " + nodeManager.getHostName().toString());

    //this.nodes results in:
      //{hadoop100:47770=host: hadoop100:47770 #containers=0 available=8192 used=0, hadoop102:54608=host: hadoop102:54608 #containers=0 available=8192 used=0}
    if (labelManager != null) {
      labelManager.activateNode(nodeManager.getNodeID(),
          nodeManager.getTotalCapability());
    }

    LOG.info("Toon :: node to be added where f is " + f + " and c is " + c + " the hadoop node should be 100 101 102 " + nodeManager.getNodeAddress());
      
    this.nodes.put(nodeManager.getNodeID(), new FiCaSchedulerNode(nodeManager,
            usePortForNodeName));
    sNodes.put(nodeManager.getNodeID(), new NodesSensitivity(nodeManager, usePortForNodeName, c));

    Resources.addTo(clusterResource, nodeManager.getTotalCapability());
    root.updateClusterResource(clusterResource);
    LOG.info("Toon :: this is the clusterResource pool " + clusterResource);
    int numNodes = numNodeManagers.incrementAndGet();
    LOG.info("Toon :: each node displayed " + this.nodes.get(nodeManager.getNodeID()));
    if (scheduleAsynchronously && numNodes == 1) {
      asyncSchedulerThread.beginSchedule();
    }
    // create 3 nodes by Toon
    /*if(c == 2){
      node1 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }else if(c == 1){
      node2 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }else{
      node3 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }*/
    // comment: actually only node 1 matters
    String temname = nodeManager.getHostName().substring(0,9);
    String c1 = "hadoop100";
    String c2 = "hadoop101";
    String c3 = "hadoop102";
    LOG.info("Toon :: CHECKED assigning node if else " + temname);
    if(temname == c3){
      LOG.info("Toon :: CHECKED " + temname);
      node3 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }else if(temname == c2){
      LOG.info("Toon :: CHECKED " + temname);
      node2 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }else{
      LOG.info("Toon :: CHECKED " + temname);
      node1 = new FiCaSchedulerNode(nodeManager, usePortForNodeName);
    }
    c-=1;
    
    LOG.info("Added node " + nodeManager.getNodeAddress() + 
        " clusterResource: " + clusterResource);
  }

  private synchronized void removeNode(RMNode nodeInfo) {
    LOG.info("Toon :: remove node");
    // update this node to node label manager
    if (labelManager != null) {
      labelManager.deactivateNode(nodeInfo.getNodeID());
    }
    
    FiCaSchedulerNode node = nodes.get(nodeInfo.getNodeID());
    if (node == null) {
      return;
    }

    Resources.subtractFrom(clusterResource, node.getRMNode().getTotalCapability());
    root.updateClusterResource(clusterResource);
    int numNodes = numNodeManagers.decrementAndGet();

    if (scheduleAsynchronously && numNodes == 0) {
      asyncSchedulerThread.suspendSchedule();
    }
    
    // Remove running containers
    List<RMContainer> runningContainers = node.getRunningContainers();
    for (RMContainer container : runningContainers) {
      completedContainer(container, 
          SchedulerUtils.createAbnormalContainerStatus(
              container.getContainerId(), 
              SchedulerUtils.LOST_CONTAINER), 
          RMContainerEventType.KILL);
    }
    
    // Remove reservations, if any
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      completedContainer(reservedContainer, 
          SchedulerUtils.createAbnormalContainerStatus(
              reservedContainer.getContainerId(), 
              SchedulerUtils.LOST_CONTAINER), 
          RMContainerEventType.KILL);
    }

    this.nodes.remove(nodeInfo.getNodeID());
    
    LOG.info("Removed node " + nodeInfo.getNodeAddress() + 
        " clusterResource: " + clusterResource);
  }
  
  @Lock(CapacityScheduler.class)
  @Override
  protected synchronized void completedContainer(RMContainer rmContainer,
      ContainerStatus containerStatus, RMContainerEventType event) {
    LOG.info("Toon :: completed container");
    if (rmContainer == null) {
      LOG.info("Null container completed...");
      return;
    }
    
    Container container = rmContainer.getContainer();
    
    // Get the application for the finished container
    FiCaSchedulerApp application =
        getCurrentAttemptForContainer(container.getId());
    ApplicationId appId =
        container.getId().getApplicationAttemptId().getApplicationId();
    if (application == null) {
      LOG.info("Container " + container + " of" + " unknown application "
          + appId + " completed with event " + event);
      return;
    }
    
    // Get the node on which the container was allocated
    FiCaSchedulerNode node = getNode(container.getNodeId());
    
    // Inform the queue
    LeafQueue queue = (LeafQueue)application.getQueue();
    queue.completedContainer(clusterResource, application, node, 
        rmContainer, containerStatus, event, null, true);

    LOG.info("Application attempt " + application.getApplicationAttemptId()
        + " released container " + container.getId() + " on node: " + node
        + " with event: " + event);
  }

  @Lock(Lock.NoLock.class)
  @VisibleForTesting
  @Override
  public FiCaSchedulerApp getApplicationAttempt(
      ApplicationAttemptId applicationAttemptId) {
    return super.getApplicationAttempt(applicationAttemptId);
  }
  
  @Lock(Lock.NoLock.class)
  public FiCaSchedulerNode getNode(NodeId nodeId) {
    return nodes.get(nodeId);
  }
  
  @Lock(Lock.NoLock.class)
  Map<NodeId, FiCaSchedulerNode> getAllNodes() {
    return nodes;
  }

  @Override
  @Lock(Lock.NoLock.class)
  public void recover(RMState state) throws Exception {
    // NOT IMPLEMENTED
  }

  @Override
  public void dropContainerReservation(RMContainer container) {
    if(LOG.isDebugEnabled()){
      LOG.debug("DROP_RESERVATION:" + container.toString());
    }
    completedContainer(container,
        SchedulerUtils.createAbnormalContainerStatus(
            container.getContainerId(),
            SchedulerUtils.UNRESERVED_CONTAINER),
        RMContainerEventType.KILL);
  }

  @Override
  public void preemptContainer(ApplicationAttemptId aid, RMContainer cont) {
    LOG.info("Toon :: preempt container");
    if(LOG.isDebugEnabled()){
      LOG.debug("PREEMPT_CONTAINER: application:" + aid.toString() +
          " container: " + cont.toString());
    }
    FiCaSchedulerApp app = getApplicationAttempt(aid);
    if (app != null) {
      app.addPreemptContainer(cont.getContainerId());
      Container container = cont.getContainer();
      LOG.info("Toon :: this container belongs to " + container.getNodeId());
    }
  }

  @Override
  public void killContainer(RMContainer cont) {
    LOG.info("Toon :: kill container");
    if (LOG.isDebugEnabled()) {
      LOG.debug("KILL_CONTAINER: container" + cont.toString());
    }
    recoverResourceRequestForContainer(cont);
    completedContainer(cont, SchedulerUtils.createPreemptedContainerStatus(
      cont.getContainerId(), SchedulerUtils.PREEMPTED_CONTAINER),
      RMContainerEventType.KILL);
  }

  @Override
  public synchronized boolean checkAccess(UserGroupInformation callerUGI,
      QueueACL acl, String queueName) {
    LOG.info("Toon :: check access");
    CSQueue queue = getQueue(queueName);
    if (queue == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("ACL not found for queue access-type " + acl
            + " for queue " + queueName);
      }
      return false;
    }
    return queue.hasAccess(acl, callerUGI);
  }

  @Override
  public List<ApplicationAttemptId> getAppsInQueue(String queueName) {
    LOG.info("Toon :: get apps in queue");
    CSQueue queue = queues.get(queueName);
    if (queue == null) {
      return null;
    }
    List<ApplicationAttemptId> apps = new ArrayList<ApplicationAttemptId>();
    queue.collectSchedulerApplications(apps);
    return apps;
  }

  private CapacitySchedulerConfiguration loadCapacitySchedulerConfiguration(
      Configuration configuration) throws IOException {
    LOG.info("Toon :: load capacity scheduler configuration");
    try {
      InputStream CSInputStream =
          this.rmContext.getConfigurationProvider()
              .getConfigurationInputStream(configuration,
                  YarnConfiguration.CS_CONFIGURATION_FILE);
      if (CSInputStream != null) {
        configuration.addResource(CSInputStream);
        return new CapacitySchedulerConfiguration(configuration, false);
      }
      return new CapacitySchedulerConfiguration(configuration, true);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private synchronized String resolveReservationQueueName(String queueName,
      ApplicationId applicationId, ReservationId reservationID) {
    LOG.info("Toon :: resolve reservation queuename");
    CSQueue queue = getQueue(queueName);
    // Check if the queue is a plan queue
    if ((queue == null) || !(queue instanceof PlanQueue)) {
      return queueName;
    }
    if (reservationID != null) {
      String resQName = reservationID.toString();
      queue = getQueue(resQName);
      if (queue == null) {
        String message =
            "Application "
                + applicationId
                + " submitted to a reservation which is not yet currently active: "
                + resQName;
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMAppRejectedEvent(applicationId, message));
        return null;
      }
      if (!queue.getParent().getQueueName().equals(queueName)) {
        String message =
            "Application: " + applicationId + " submitted to a reservation "
                + resQName + " which does not belong to the specified queue: "
                + queueName;
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMAppRejectedEvent(applicationId, message));
        return null;
      }
      // use the reservation queue to run the app
      queueName = resQName;
    } else {
      // use the default child queue of the plan for unreserved apps
      queueName = queueName + PlanQueue.DEFAULT_QUEUE_SUFFIX;
    }
    return queueName;
  }

  @Override
  public synchronized void removeQueue(String queueName)
      throws SchedulerDynamicEditException {
        LOG.info("Toon :: remove queue");
    LOG.info("Removing queue: " + queueName);
    CSQueue q = this.getQueue(queueName);
    if (!(q instanceof ReservationQueue)) {
      throw new SchedulerDynamicEditException("The queue that we are asked "
          + "to remove (" + queueName + ") is not a ReservationQueue");
    }
    ReservationQueue disposableLeafQueue = (ReservationQueue) q;
    // at this point we should have no more apps
    if (disposableLeafQueue.getNumApplications() > 0) {
      throw new SchedulerDynamicEditException("The queue " + queueName
          + " is not empty " + disposableLeafQueue.getApplications().size()
          + " active apps " + disposableLeafQueue.pendingApplications.size()
          + " pending apps");
    }

    ((PlanQueue) disposableLeafQueue.getParent()).removeChildQueue(q);
    this.queues.remove(queueName);
    LOG.info("Removal of ReservationQueue " + queueName + " has succeeded");
  }

  @Override
  public synchronized void addQueue(Queue queue)
      throws SchedulerDynamicEditException {
        LOG.info("Toon :: add queue");

    if (!(queue instanceof ReservationQueue)) {
      throw new SchedulerDynamicEditException("Queue " + queue.getQueueName()
          + " is not a ReservationQueue");
    }

    ReservationQueue newQueue = (ReservationQueue) queue;

    if (newQueue.getParent() == null
        || !(newQueue.getParent() instanceof PlanQueue)) {
      throw new SchedulerDynamicEditException("ParentQueue for "
          + newQueue.getQueueName()
          + " is not properly set (should be set and be a PlanQueue)");
    }

    PlanQueue parentPlan = (PlanQueue) newQueue.getParent();
    String queuename = newQueue.getQueueName();
    parentPlan.addChildQueue(newQueue);
    this.queues.put(queuename, newQueue);
    LOG.info("Creation of ReservationQueue " + newQueue + " succeeded");
  }

  @Override
  public synchronized void setEntitlement(String inQueue,
      QueueEntitlement entitlement) throws SchedulerDynamicEditException,
      YarnException {
        LOG.info("Toon :: set entitlement");
    LeafQueue queue = getAndCheckLeafQueue(inQueue);
    ParentQueue parent = (ParentQueue) queue.getParent();

    if (!(queue instanceof ReservationQueue)) {
      throw new SchedulerDynamicEditException("Entitlement can not be"
          + " modified dynamically since queue " + inQueue
          + " is not a ReservationQueue");
    }

    if (!(parent instanceof PlanQueue)) {
      throw new SchedulerDynamicEditException("The parent of ReservationQueue "
          + inQueue + " must be an PlanQueue");
    }

    ReservationQueue newQueue = (ReservationQueue) queue;

    float sumChilds = ((PlanQueue) parent).sumOfChildCapacities();
    float newChildCap = sumChilds - queue.getCapacity() + entitlement.getCapacity();

    if (newChildCap >= 0 && newChildCap < 1.0f + CSQueueUtils.EPSILON) {
      // note: epsilon checks here are not ok, as the epsilons might accumulate
      // and become a problem in aggregate
      if (Math.abs(entitlement.getCapacity() - queue.getCapacity()) == 0
          && Math.abs(entitlement.getMaxCapacity() - queue.getMaximumCapacity()) == 0) {
        return;
      }
      newQueue.setEntitlement(entitlement);
    } else {
      throw new SchedulerDynamicEditException(
          "Sum of child queues would exceed 100% for PlanQueue: "
              + parent.getQueueName());
    }
    LOG.info("Set entitlement for ReservationQueue " + inQueue + "  to "
        + queue.getCapacity() + " request was (" + entitlement.getCapacity() + ")");
  }

  @Override
  public synchronized String moveApplication(ApplicationId appId,
      String targetQueueName) throws YarnException {
    LOG.info("Toon :: move application");
    FiCaSchedulerApp app =
        getApplicationAttempt(ApplicationAttemptId.newInstance(appId, 0));
    String sourceQueueName = app.getQueue().getQueueName();
    LeafQueue source = getAndCheckLeafQueue(sourceQueueName);
    String destQueueName = handleMoveToPlanQueue(targetQueueName);
    LeafQueue dest = getAndCheckLeafQueue(destQueueName);
    // Validation check - ACLs, submission limits for user & queue
    String user = app.getUser();
    try {
      dest.submitApplication(appId, user, destQueueName);
    } catch (AccessControlException e) {
      throw new YarnException(e);
    }
    // Move all live containers
    for (RMContainer rmContainer : app.getLiveContainers()) {
      source.detachContainer(clusterResource, app, rmContainer);
      // attach the Container to another queue
      dest.attachContainer(clusterResource, app, rmContainer);
    }
    // Detach the application..
    source.finishApplicationAttempt(app, sourceQueueName);
    source.getParent().finishApplication(appId, app.getUser());
    // Finish app & update metrics
    app.move(dest);
    // Submit to a new queue
    dest.submitApplicationAttempt(app, user);
    applications.get(appId).setQueue(dest);
    LOG.info("App: " + app.getApplicationId() + " successfully moved from "
        + sourceQueueName + " to: " + destQueueName);
    return targetQueueName;
  }

  /**
   * Check that the String provided in input is the name of an existing,
   * LeafQueue, if successful returns the queue.
   *
   * @param queue
   * @return the LeafQueue
   * @throws YarnException
   */
  private LeafQueue getAndCheckLeafQueue(String queue) throws YarnException {
    LOG.info("Toon :: get and check leafqueue");
    CSQueue ret = this.getQueue(queue);
    if (ret == null) {
      throw new YarnException("The specified Queue: " + queue
          + " doesn't exist");
    }
    if (!(ret instanceof LeafQueue)) {
      throw new YarnException("The specified Queue: " + queue
          + " is not a Leaf Queue. Move is supported only for Leaf Queues.");
    }
    return (LeafQueue) ret;
  }

  /** {@inheritDoc} */
  @Override
  public EnumSet<SchedulerResourceTypes> getSchedulingResourceTypes() {
    LOG.info("Toon :: get scheduling resource types");
    if (calculator.getClass().getName()
      .equals(DefaultResourceCalculator.class.getName())) {
      return EnumSet.of(SchedulerResourceTypes.MEMORY);
    }
    return EnumSet
      .of(SchedulerResourceTypes.MEMORY, SchedulerResourceTypes.CPU);
  }
  
  private String handleMoveToPlanQueue(String targetQueueName) {
    LOG.info("Toon :: handle move to planqueue");
    CSQueue dest = getQueue(targetQueueName);
    if (dest != null && dest instanceof PlanQueue) {
      // use the default child reservation queue of the plan
      targetQueueName = targetQueueName + PlanQueue.DEFAULT_QUEUE_SUFFIX;
    }
    return targetQueueName;
  }

  @Override
  public Set<String> getPlanQueues() {
    LOG.info("Toon :: get plan queues");
    Set<String> ret = new HashSet<String>();
    for (Map.Entry<String, CSQueue> l : queues.entrySet()) {
      if (l.getValue() instanceof PlanQueue) {
        ret.add(l.getKey());
      }
    }
    return ret;
  }
}