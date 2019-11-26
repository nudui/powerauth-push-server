/*
 * Copyright 2016 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.getlime.push.controller.rest;

import io.getlime.core.rest.model.base.request.ObjectRequest;
import io.getlime.core.rest.model.base.response.Response;
import io.getlime.powerauth.soap.v3.ActivationStatus;
import io.getlime.powerauth.soap.v3.GetActivationStatusResponse;
import io.getlime.push.configuration.PushServiceConfiguration;
import io.getlime.push.errorhandling.exceptions.PushServerException;
import io.getlime.push.model.request.CreateDeviceForActivationsRequest;
import io.getlime.push.model.request.CreateDeviceRequest;
import io.getlime.push.model.request.DeleteDeviceRequest;
import io.getlime.push.model.request.UpdateDeviceStatusRequest;
import io.getlime.push.model.validator.CreateDeviceRequestValidator;
import io.getlime.push.model.validator.DeleteDeviceRequestValidator;
import io.getlime.push.model.validator.UpdateDeviceStatusRequestValidator;
import io.getlime.push.repository.PushDeviceRepository;
import io.getlime.push.repository.model.PushDeviceRegistrationEntity;
import io.getlime.security.powerauth.soap.spring.client.PowerAuthServiceClient;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller responsible for device registration related business processes.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Controller
@RequestMapping(value = "push/device")
public class PushDeviceController {

    private static final Logger logger = LoggerFactory.getLogger(PushDeviceController.class);

    private final PushDeviceRepository pushDeviceRepository;
    private final PowerAuthServiceClient client;
    private final PushServiceConfiguration config;

    @Autowired
    public PushDeviceController(PushDeviceRepository pushDeviceRepository, PowerAuthServiceClient client, PushServiceConfiguration config) {
        this.pushDeviceRepository = pushDeviceRepository;
        this.client = client;
        this.config = config;
    }

    /**
     * Create a new device registration.
     * @param request Device registration request.
     * @return Device registration status.
     * @throws PushServerException In case request object is invalid.
     */
    @RequestMapping(value = "create", method = RequestMethod.POST)
    @ApiOperation(value = "Create a device",
                  notes = "Create a new device push token (platform specific). The call must include an activation ID, so that the token is associated with given user." +
                          "Request body should contain application ID, device token, device's platform and an activation ID. " +
                          "If such device already exist, date on last registration is updated and also platform might be changed\n" +
                          "\n---" +
                          "Note: Since this endpoint is usually called by the back-end service, it is not secured in any way. " +
                          "It's the service that calls this endpoint responsibility to assure that the device is somehow authenticated before the push token is assigned with given activation ID," +
                          " so that there are no incorrect bindings.")
    public @ResponseBody Response createDevice(@RequestBody ObjectRequest<CreateDeviceRequest> request) throws PushServerException {
        CreateDeviceRequest requestedObject = request.getRequestObject();
        String errorMessage = CreateDeviceRequestValidator.validate(requestedObject);
        if (errorMessage != null) {
            throw new PushServerException(errorMessage);
        }
        Long appId = requestedObject.getAppId();
        String pushToken = requestedObject.getToken();
        String platform = requestedObject.getPlatform();
        String activationId = requestedObject.getActivationId();
        List<PushDeviceRegistrationEntity> devices = lookupDeviceRegistrations(appId, activationId, pushToken);
        PushDeviceRegistrationEntity device;
        if (devices.isEmpty()) {
            // The device registration is new, create a new entity.
            device = initDeviceRegistrationEntity(appId, pushToken);
        } else if (devices.size() == 1) {
            // An existing row was found by one of the lookup methods, update this row. This means that either:
            // 1. A row with same activation ID and push token is updated, in this case only the last registration timestamp changes.
            // 2. A row with same activation ID but different push token is updated. A new push token has been issued by Google or Apple for an activation.
            // 3. A row with same push token but different activation ID is updated. The user removed an activation and created a new one, the push token remains the same.
            device = devices.get(0);
        } else {
            // Multiple existing rows have been found. This can only occur during lookup by push token.
            // Push token can be associated with multiple activations only when associated activations are enabled.
            // Push device registration must be done using /push/device/create/multi endpoint in this case.
            throw new PushServerException("Multiple device registrations found for push token. Use the /push/device/create/multi endpoint for this scenario.");
        }
        device.setTimestampLastRegistered(new Date());
        device.setPlatform(platform);
        updateActivationForDevice(device, activationId);
        pushDeviceRepository.save(device);
        return new Response();
    }

    /**
     * Create a new device registration for multiple associated activations.
     * @param request Device registration request.
     * @return Device registration status.
     * @throws PushServerException In case request object is invalid.
     */
    @RequestMapping(value = "create/multi", method = RequestMethod.POST)
    @ApiOperation(value = "Create a device for multiple associated activations",
            notes = "Create a new device push token (platform specific). The call must include one or more activation IDs." +
                    "Request body should contain application ID, device token, device's platform and list of activation IDs. " +
                    "If such device already exist, date on last registration is updated and also platform might be changed\n" +
                    "\n---" +
                    "Note: Since this endpoint is usually called by the back-end service, it is not secured in any way. " +
                    "It's the service that calls this endpoint responsibility to assure that the device is somehow authenticated before the push token is assigned with given activation IDs," +
                    " so that there are no incorrect bindings.")
    public @ResponseBody Response createDeviceMultipleActivations(@RequestBody ObjectRequest<CreateDeviceForActivationsRequest> request) throws PushServerException {
        CreateDeviceForActivationsRequest requestedObject = request.getRequestObject();
        String errorMessage;
        if (!config.isRegistrationOfMultipleActivationsEnabled()) {
            errorMessage = "Registration of multiple associated activations per device is not enabled.";
        } else {
            errorMessage = CreateDeviceRequestValidator.validate(requestedObject);
        }
        if (errorMessage != null) {
            throw new PushServerException(errorMessage);
        }
        Long appId = requestedObject.getAppId();
        String pushToken = requestedObject.getToken();
        String platform = requestedObject.getPlatform();
        List<String> activationIds = requestedObject.getActivationIds();

        // Initialize loop variables.
        AtomicBoolean registrationFailed = new AtomicBoolean(false);
        Set<Long> usedDeviceRegistrationIds = new HashSet<>();

        activationIds.forEach(activationId -> {
            try {
                List<PushDeviceRegistrationEntity> devices = lookupDeviceRegistrations(appId, activationId, pushToken);
                PushDeviceRegistrationEntity device;
                if (devices.isEmpty()) {
                    // The device registration is new, create a new entity.
                    device = initDeviceRegistrationEntity(appId, pushToken);
                } else if (devices.size() == 1) {
                    device = devices.get(0);
                    if (usedDeviceRegistrationIds.contains(device.getId())) {
                        // The row has already been used for another activation within this request. Create a new row instead.
                        device = initDeviceRegistrationEntity(appId, pushToken);
                    }
                    // Add the device registration entity ID into list of used entities to avoid merging multiple rows into one.
                    usedDeviceRegistrationIds.add(device.getId());
                } else {
                    // Multiple existing rows have been found. This can only occur during lookup by push token.
                    // It is not clear how original rows should be mapped to new rows because they were not looked up
                    // using an activation ID. Delete existing rows and create a new row.
                    devices.forEach(pushDeviceRepository::delete);
                    device = initDeviceRegistrationEntity(appId, pushToken);
                }
                device.setAppId(appId);
                device.setPushToken(pushToken);
                device.setTimestampLastRegistered(new Date());
                device.setPlatform(platform);
                updateActivationForDevice(device, activationId);
                pushDeviceRepository.save(device);
            } catch (PushServerException ex) {
                logger.error(ex.getMessage(), ex);
                registrationFailed.set(true);
            }
        });

        if (registrationFailed.get()) {
            throw new PushServerException("Device registration failed");
        }

        return new Response();
    }

    /**
     * Initialize a new device registration entity for given app ID and push token.
     * @param appId App ID.
     * @param pushToken Push token.
     * @return New device registration entity.
     */
    private PushDeviceRegistrationEntity initDeviceRegistrationEntity(Long appId, String pushToken) {
        PushDeviceRegistrationEntity device = new PushDeviceRegistrationEntity();
        device.setAppId(appId);
        device.setPushToken(pushToken);
        return device;
    }

    /**
     * Lookup device registrations using app ID, activation ID and push token.
     * <br/>
     * The query priorities are ranging from most exact to least exact match:
     * <ul>
     *     <li>Lookup by activation ID and push token.</li>
     *     <li>Lookup by activation ID.</li>
     *     <li>Lookup by application ID and push token.</li>
     * </ul>
     * @param appId Application ID.
     * @param activationId Activation ID.
     * @param pushToken Push token.
     * @return List of found device registration entities.
     */
    private List<PushDeviceRegistrationEntity> lookupDeviceRegistrations(Long appId, String activationId, String pushToken) throws PushServerException {
        List<PushDeviceRegistrationEntity> deviceRegistrations;
        // At first, lookup the device registrations by match on activationId and pushToken.
        deviceRegistrations = pushDeviceRepository.findByActivationIdAndPushToken(activationId, pushToken);
        if (!deviceRegistrations.isEmpty()) {
            if (deviceRegistrations.size() != 1) {
                throw new PushServerException("Multiple device registrations found during lookup by activation ID and push token. Please delete duplicate rows and make sure database indexes have been applied on push_device_registration table.");
            }
            return deviceRegistrations;
        }
        // Second, lookup the device registrations by match on activationId.
        deviceRegistrations = pushDeviceRepository.findByActivationId(activationId);
        if (!deviceRegistrations.isEmpty()) {
            if (deviceRegistrations.size() != 1) {
                throw new PushServerException("Multiple device registrations found during lookup by activation ID. Please delete duplicate rows and make sure database indexes have been applied on push_device_registration table.");
            }
            return deviceRegistrations;
        }
        // Third, lookup the device registration by match on appId and pushToken. Multiple results can be returned in this case, this is a multi-activation scenario.
        deviceRegistrations = pushDeviceRepository.findByAppIdAndPushToken(appId, pushToken);
        // The final result is definitive, either device registrations were found by push token or none were found at all.
        return deviceRegistrations;
    }

    /**
     * Update activation for given device in case activation exists in PowerAuth server and it is not in REMOVED state.
     * @param device Push device registration entity.
     * @param activationId Activation ID.
     */
    private void updateActivationForDevice(PushDeviceRegistrationEntity device, String activationId) {
        final GetActivationStatusResponse activation = client.getActivationStatus(activationId);
        if (activation != null && !ActivationStatus.REMOVED.equals(activation.getActivationStatus())) {
            device.setActivationId(activationId);
            device.setActive(activation.getActivationStatus().equals(ActivationStatus.ACTIVE));
            device.setUserId(activation.getUserId());
        }
    }

    /**
     * Update activation status for given device registration.
     * @param request Status update request.
     * @return Status update response.
     * @throws PushServerException In case request object is invalid.
     */
    @RequestMapping(value = "status/update", method = RequestMethod.POST)
    @ApiOperation(value = "Update device status",
                  notes = "Update the status of given device registration based on the associated activation ID. " +
                          "This can help assure that registration is in non-active state and cannot receive personal messages.")
    public @ResponseBody Response updateDeviceStatus(@RequestBody UpdateDeviceStatusRequest request) throws PushServerException {
        String errorMessage = UpdateDeviceStatusRequestValidator.validate(request);
        if (errorMessage != null) {
            throw new PushServerException(errorMessage);
        }
        String activationId = request.getActivationId();
        List<PushDeviceRegistrationEntity> device = pushDeviceRepository.findByActivationId(activationId);
        if (device != null)  {
            ActivationStatus status = client.getActivationStatus(activationId).getActivationStatus();
            for (PushDeviceRegistrationEntity registration: device) {
                registration.setActive(status.equals(ActivationStatus.ACTIVE));
                pushDeviceRepository.save(registration);
            }
        }
        return new Response();
    }

    /**
     * Remove device registration with given push token.
     * @param request Remove registration request.
     * @return Removal status response.
     * @throws PushServerException In case request object is invalid.
     */
    @RequestMapping(value = "delete", method = RequestMethod.POST)
    @ApiOperation(value = "Delete a device",
                  notes = "Remove device identified by application ID and device token. " +
                          "If device identifiers don't match, nothing happens")
    public @ResponseBody Response deleteDevice(@RequestBody ObjectRequest<DeleteDeviceRequest> request) throws PushServerException {
        DeleteDeviceRequest requestedObject = request.getRequestObject();
        String errorMessage = DeleteDeviceRequestValidator.validate(requestedObject);
        if (errorMessage != null) {
            throw new PushServerException(errorMessage);
        }
        Long appId = requestedObject.getAppId();
        String pushToken = requestedObject.getToken();
        List<PushDeviceRegistrationEntity> devices = pushDeviceRepository.findByAppIdAndPushToken(appId, pushToken);
        if (!devices.isEmpty())  {
            pushDeviceRepository.deleteAll(devices);
        }
        return new Response();
    }
}