/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.somfycul.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.SerialPort;

/**
 * The {@link CULHandler} is responsible for handling commands, which are
 * sent via the CUL stick.
 *
 * @author Daniel Weisser - Initial contribution
 *
 *         TODO Adapt with org.openhab.binding.digiplex.internal.handler.DigiplexBridgeHandler
 */
@NonNullByDefault
public class CULHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CULHandler.class);

    private @Nullable CULConfiguration config;

    private static final String GNU_IO_RXTX_SERIAL_PORTS = "gnu.io.rxtx.SerialPorts";

    private long lastCommandTime = 0;

    @Nullable
    private CommPortIdentifier portId;
    @Nullable
    private SerialPort serialPort;
    @Nullable
    private OutputStream outputStream;
    @Nullable
    private InputStream inputStream;

    public CULHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // the bridge does not have any channels
    }

    /**
     * Executes the given {@link SomfyCommand} for the given {@link Thing} (RTS Device).
     *
     * @param somfyDevice the RTS Device which is the receiver of the command.
     * @param somfyCommand
     * @return
     */
    public boolean executeCULCommand(Thing somfyDevice, SomfyCommand somfyCommand, String rollingCode, String adress) {
        String culCommand = "Ys" + "A1" + somfyCommand.getActionKey() + "0" + rollingCode + adress;
        logger.info("Send message {} for thing {}", culCommand, somfyDevice.getLabel());
        return writeString(culCommand);
    }

    /**
     * Sends a string to the serial port of this device.
     * The writing of the msg is executed synchronized, so it's guaranteed that the device doesn't get
     * multiple messages concurrently.
     *
     * @param msg
     *            the string to send
     * @return true, if the message has been transmitted successfully, otherwise false.
     */
    protected synchronized boolean writeString(final String msg) {
        logger.debug("Trying to write '{}' to serial port {}", msg, portId.getName());

        // TODO Check for status of bridge
        final long earliestNextExecution = lastCommandTime + 100;
        while (earliestNextExecution > System.currentTimeMillis()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                return false;
            }
        }
        try {
            outputStream.write((msg + "\n").getBytes());
            outputStream.flush();
            lastCommandTime = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            logger.error("Error writing '{}' to serial port {}: {}", msg, portId.getName(), e.getMessage());
        }
        return false;
    }

    /**
     * Registers the given port as system property {@value #GNU_IO_RXTX_SERIAL_PORTS}. The method is capable of
     * extending the system property, if any other ports are already registered.
     *
     * @param port the port to be registered
     */
    private void initSerialPort(String port) {
        String serialPortsProperty = System.getProperty(GNU_IO_RXTX_SERIAL_PORTS);
        Set<String> serialPorts = null;

        if (serialPortsProperty != null) {
            serialPorts = Stream.of(serialPortsProperty.split(":")).collect(Collectors.toSet());
        } else {
            serialPorts = new HashSet<>();
        }
        if (serialPorts.add(port)) {
            logger.debug("Added {} to the {} system property.", port, GNU_IO_RXTX_SERIAL_PORTS);
            System.setProperty(GNU_IO_RXTX_SERIAL_PORTS, serialPorts.stream().collect(Collectors.joining(":")));
        }
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing!");
        config = getConfigAs(CULConfiguration.class);
        if (config.port == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port must be set!");
            return;
        }
        logger.info("got port: {}", config.port);
        initSerialPort(config.port);
        try {
            portId = CommPortIdentifier.getPortIdentifier(config.port);
            // initialize serial port
            serialPort = portId.open("openHAB", 2000);
            // set port parameters
            serialPort.setSerialPortParams(config.baudrate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            // TODO Check version of CUL
            updateStatus(ThingStatus.ONLINE);
            logger.debug("Finished initializing!");
        } catch (NoSuchPortException e) {
            // enumerate the port identifiers in the exception to be helpful
            final StringBuilder sb = new StringBuilder();
            @SuppressWarnings("unchecked")
            Enumeration<CommPortIdentifier> portList = CommPortIdentifier.getPortIdentifiers();
            while (portList.hasMoreElements()) {
                final CommPortIdentifier id = portList.nextElement();
                if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                    sb.append(id.getName() + "\n");
                }
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Serial port '" + config.port + "' could not be found. Available ports are:\n" + sb.toString());
        } catch (Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error("An error occurred while initializing the CUL connection.", e);
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "An error occurred while initializing the CUL connection: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        IOUtils.closeQuietly(outputStream);
        IOUtils.closeQuietly(inputStream);
        if (serialPort != null) {
            serialPort.removeEventListener();
            serialPort.close();
        }
    }
}
