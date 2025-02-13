// Copyright (c) 2007-2023 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.test.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;

import com.rabbitmq.client.test.functional.ClusteredTestBase;

/**
 * From bug 19844 - we want to be sure that publish vs everything else can't
 * happen out of order
 */
public class EffectVisibilityCrossNodeTest extends ClusteredTestBase {
    private final String[] queues = new String[QUEUES];

    ExecutorService executorService;

    @Override
    protected void createResources() throws IOException {
        for (int i = 0; i < queues.length ; i++) {
            queues[i] = alternateChannel.queueDeclare("", false, false, true, null).getQueue();
            alternateChannel.queueBind(queues[i], "amq.fanout", "");
        }
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void releaseResources() throws IOException {
        executorService.shutdownNow();
        for (int i = 0; i < queues.length ; i++) {
            alternateChannel.queueDelete(queues[i]);
        }
    }

    private static final int QUEUES = 5;
    private static final int BATCHES = 100;
    private static final int MESSAGES_PER_BATCH = 5;

    private static final byte[] msg = "".getBytes();

    @Test public void effectVisibility() throws Exception {
    // the test bulk is asynchronous because this test has a history of hanging
    Future<Void> task =
        executorService.submit(
            () -> {
              for (int i = 0; i < BATCHES; i++) {
                Thread.sleep(10); // to avoid flow control for the connection
                for (int j = 0; j < MESSAGES_PER_BATCH; j++) {
                  channel.basicPublish("amq.fanout", "", null, msg);
                }
                for (int j = 0; j < queues.length; j++) {
                  String queue = queues[j];
                  long timeout = 10 * 1000;
                  long waited = 0;
                  int purged = 0;
                  while (waited < timeout) {
                    purged += channel.queuePurge(queue).getMessageCount();
                    if (purged == MESSAGES_PER_BATCH) {
                      break;
                    }
                    Thread.sleep(10);
                    waited += 10;
                  }
                  assertEquals(MESSAGES_PER_BATCH, purged, "Queue " + queue + " should have been purged after 10 seconds");
                }
              }
              return null;
            });
            task.get(1, TimeUnit.MINUTES);
    }
}
