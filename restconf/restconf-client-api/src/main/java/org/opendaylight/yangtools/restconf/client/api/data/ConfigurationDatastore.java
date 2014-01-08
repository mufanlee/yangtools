/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.restconf.client.api.data;

import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;

import com.google.common.util.concurrent.ListenableFuture;

public interface ConfigurationDatastore extends Datastore {
    ListenableFuture<RpcResult<Boolean>> deleteData(InstanceIdentifier<?> path);
    ListenableFuture<RpcResult<Boolean>> putData(InstanceIdentifier<?> path);
}
