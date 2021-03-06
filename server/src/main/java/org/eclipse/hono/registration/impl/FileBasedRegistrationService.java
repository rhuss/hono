/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */

package org.eclipse.hono.registration.impl;

import static java.net.HttpURLConnection.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.hono.util.RegistrationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A registration adapter that keeps all data in memory but is backed by a file.
 * <p>
 * On startup this adapter loads all registered devices from a file. On shutdown all
 * devices kept in memory are written to the file.
 */
@Repository
public class FileBasedRegistrationService extends BaseRegistrationService {

    private static final String FIELD_DATA = "data";
    private static final String FIELD_HONO_ID = "id";
    private static final String ARRAY_DEVICES = "devices";
    private static final String FIELD_TENANT = "tenant";

    private final Map<String, Map<String, JsonObject>> identities = new HashMap<>();
    // the name of the file used to persist the registry content
    @Value("${hono.registration.filename:device-identities.json}")
    private String filename;

    @Override
    protected void doStart(Future<Void> startFuture) throws Exception {

        if (filename != null) {
            loadRegistrationData();
            vertx.setPeriodic(3000, saveIdentities -> {
                saveToFile(Future.future());
            });
        }
        startFuture.complete();
    }

    private void loadRegistrationData() {
        if (filename != null) {
            final FileSystem fs = vertx.fileSystem();
            log.debug("trying to load device registration information from file {}", filename);
            if (fs.existsBlocking(filename)) {
                final AtomicInteger deviceCount = new AtomicInteger();
                fs.readFile(filename, readAttempt -> {
                   if (readAttempt.succeeded()) {
                       JsonArray allObjects = new JsonArray(new String(readAttempt.result().getBytes()));
                       for (Object obj : allObjects) {
                           JsonObject tenant = (JsonObject) obj;
                           String tenantId = tenant.getString(FIELD_TENANT);
                           Map<String, JsonObject> deviceMap = new HashMap<>();
                           for (Object deviceObj : tenant.getJsonArray(ARRAY_DEVICES)) {
                               JsonObject device = (JsonObject) deviceObj;
                               deviceMap.put(device.getString(FIELD_HONO_ID), device.getJsonObject(FIELD_DATA));
                               deviceCount.incrementAndGet();
                           }
                           identities.put(tenantId, deviceMap);
                       }
                       log.info("successfully loaded {} device identities from file [{}]", deviceCount.get(), filename);
                   } else {
                       log.warn("could not load device identities from file [{}]", filename, readAttempt.cause());
                   }
                });
            } else {
                log.debug("device identity file {} does not exist", filename);
            }
        }
    }

    @Override
    protected void doStop(Future<Void> stopFuture) {
        saveToFile(stopFuture);
    }

    private void saveToFile(final Future<Void> writeResult) {
        final FileSystem fs = vertx.fileSystem();
        if (!fs.existsBlocking(filename)) {
            fs.createFileBlocking(filename);
        }
        final AtomicInteger idCount = new AtomicInteger();
        JsonArray tenants = new JsonArray();
        for (Entry<String, Map<String, JsonObject>> entry : identities.entrySet()) {
            JsonArray devices = new JsonArray();
            for (Entry<String, JsonObject> deviceEntry : entry.getValue().entrySet()) {
                devices.add(
                        new JsonObject()
                            .put(FIELD_HONO_ID, deviceEntry.getKey())
                            .put(FIELD_DATA, deviceEntry.getValue()));
                idCount.incrementAndGet();
            }
            tenants.add(
                    new JsonObject()
                        .put(FIELD_TENANT, entry.getKey())
                        .put(ARRAY_DEVICES, devices));
        }
        fs.writeFile(filename, Buffer.factory.buffer(tenants.encodePrettily()), writeAttempt -> {
            if (writeAttempt.succeeded()) {
                log.trace("successfully wrote {} device identities to file {}", idCount.get(), filename);
                writeResult.complete();
            } else {
                log.warn("could not write device identities to file {}", filename, writeAttempt.cause());
                writeResult.fail(writeAttempt.cause());
            }
        });
    }

    @Override
    public void getDevice(final String tenantId, final String deviceId, final Handler<AsyncResult<RegistrationResult>> resultHandler) {
        resultHandler.handle(Future.succeededFuture(getDevice(tenantId, deviceId)));
    }

    public RegistrationResult getDevice(final String tenantId, final String deviceId) {
        final Map<String, JsonObject> devices = identities.get(tenantId);
        if (devices != null) {
            JsonObject data = devices.get(deviceId);
            if (data != null) {
                return RegistrationResult.from(HTTP_OK, getResult(deviceId, data));
            }
        }
        return RegistrationResult.from(HTTP_NOT_FOUND);
    }

    @Override
    public void findDevice(final String tenantId, final String key, final String value, final Handler<AsyncResult<RegistrationResult>> resultHandler) {
        resultHandler.handle(Future.succeededFuture(findDevice(tenantId, key, value)));
    }

    public RegistrationResult findDevice(final String tenantId, final String key, final String value) {
        final Map<String, JsonObject> devices = identities.get(tenantId);
        if (devices != null) {
            for (Entry<String, JsonObject> entry : devices.entrySet()) {
                if (value.equals(entry.getValue().getString(key))) {
                    return RegistrationResult.from(HTTP_OK, getResult(entry.getKey(), entry.getValue()));
                }
            }
        }
        return RegistrationResult.from(HTTP_NOT_FOUND);
    }

    private static JsonObject getResult(final String id, final JsonObject data) {
        return new JsonObject().put(FIELD_HONO_ID, id).put(FIELD_DATA, data);
    }

    @Override
    public void removeDevice(final String tenantId, final String deviceId, final Handler<AsyncResult<RegistrationResult>> resultHandler) {

        resultHandler.handle(Future.succeededFuture(removeDevice(tenantId, deviceId)));
    }

    public RegistrationResult removeDevice(final String tenantId, final String deviceId) {

        final Map<String, JsonObject> devices = identities.get(tenantId);
        if (devices != null && devices.containsKey(deviceId)) {
            return RegistrationResult.from(HTTP_OK, getResult(deviceId, devices.remove(deviceId)));
        } else {
            return RegistrationResult.from(HTTP_NOT_FOUND);
        }
    }

    @Override
    public void addDevice(final String tenantId, final String deviceId, final JsonObject data, final Handler<AsyncResult<RegistrationResult>> resultHandler) {

        resultHandler.handle(Future.succeededFuture(addDevice(tenantId, deviceId, data)));
    }

    public RegistrationResult addDevice(final String tenantId, final String deviceId, final JsonObject data) {

        JsonObject obj = data != null ? data : new JsonObject();
        if (getDevicesForTenant(tenantId).putIfAbsent(deviceId, obj) == null) {
            return RegistrationResult.from(HTTP_CREATED);
        } else {
            return RegistrationResult.from(HTTP_CONFLICT);
        }
    }

    @Override
    public void updateDevice(final String tenantId, final String deviceId, final JsonObject data, final Handler<AsyncResult<RegistrationResult>> resultHandler) {

        resultHandler.handle(Future.succeededFuture(updateDevice(tenantId, deviceId, data)));
    }

    public RegistrationResult updateDevice(final String tenantId, final String deviceId, final JsonObject data) {

        JsonObject obj = data != null ? data : new JsonObject();
        final Map<String, JsonObject> devices = identities.get(tenantId);
        if (devices != null && devices.containsKey(deviceId)) {
            return RegistrationResult.from(HTTP_OK, getResult(deviceId, devices.put(deviceId, obj)));
        } else {
            return RegistrationResult.from(HTTP_NOT_FOUND);
        }
    }

    private Map<String, JsonObject> getDevicesForTenant(final String tenantId) {
        return identities.computeIfAbsent(tenantId, id -> new ConcurrentHashMap<>());
    }

    /**
     * Removes all devices from the registry.
     */
    public void clear() {
        identities.clear();
    }

    @Override
    public String toString() {
        return String.format("%s[filename=%s]", FileBasedRegistrationService.class.getSimpleName(), filename);
    }
}
