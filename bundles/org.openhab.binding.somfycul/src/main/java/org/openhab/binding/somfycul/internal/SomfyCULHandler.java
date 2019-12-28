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

import static org.openhab.binding.somfycul.internal.SomfyCULBindingConstants.*;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.ConfigConstants;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SomfyCULHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Daniel Weisser - Initial contribution
 */
@NonNullByDefault
public class SomfyCULHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(SomfyCULHandler.class);

    private @Nullable SomfyCULConfiguration config;

    private File propertyFile;
    private Properties p;

    /**
     * Initializes the thing. As persistent state is necessary the properties are stored in the user data directory and
     * fetched within the constructor.
     *
     * @param thing
     */
    public SomfyCULHandler(Thing thing) {
        super(thing);
        propertyFile = new File(ConfigConstants.getUserDataFolder() + File.separator + "somfycul" + File.separator
                + thing.getUID().getAsString().replace(':', '_') + ".properties");
        p = initProperties();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.info("channelUID: {}, command: {}", channelUID, command);
        SomfyCommand somfyCommand = null;
        if (channelUID.getId().equals(POSITION)) {
            if (command instanceof UpDownType) {
                switch ((UpDownType) command) {
                    case UP:
                        somfyCommand = SomfyCommand.UP;
                        break;
                    case DOWN:
                        somfyCommand = SomfyCommand.DOWN;
                        break;
                }
            } else if (command instanceof StopMoveType) {
                switch ((StopMoveType) command) {
                    case STOP:
                        somfyCommand = SomfyCommand.MY;
                        break;
                    default:
                        break;
                }

            }
        } else if (channelUID.getId().equals(PROGRAM)) {
            if (command instanceof OnOffType) {
                // Don't check for on/off - always trigger program mode
                somfyCommand = SomfyCommand.PROG;
            }
        }
        Bridge bridge = getBridge();
        if (somfyCommand != null && bridge != null) {
            // We delegate the execution to the bridge handler
            ThingHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler instanceof CULHandler) {
                logger.debug("rolling code before command {}", p.getProperty("rollingCode"));

                boolean executedSuccessfully = ((CULHandler) bridgeHandler).executeCULCommand(getThing(), somfyCommand,
                        p.getProperty("rollingCode"), p.getProperty("address"));
                if (executedSuccessfully && command instanceof State) {
                    updateState(channelUID, (State) command);

                    long newRollingCode = Long.decode("0x" + p.getProperty("rollingCode")) + 1;
                    p.setProperty("rollingCode", String.format("%04X", newRollingCode));
                    logger.debug("Updated rolling code to {}", p.getProperty("rollingCode"));
                    p.setProperty("address", p.getProperty("address"));

                    try {
                        p.store(new FileWriter(propertyFile), "Last command: " + somfyCommand);
                    } catch (IOException e) {
                        logger.error("Error occured on writing the property file.", e);
                    }
                }
            }
        }
    }

    /**
     * The roller shutter is by default initialized and set to online, as there is no feedback that can check if the
     * shutter is available.
     */
    @Override
    public void initialize() {
        logger.debug("Start initializing!");
        config = getConfigAs(SomfyCULConfiguration.class);
        updateStatus(ThingStatus.ONLINE);
        logger.debug("Finished initializing!");
    }

    /**
     * Initializes the properties for the thing (shutter).
     *
     * @return Valid properties (address and rollingCode)
     */
    private Properties initProperties() {
        p = new Properties();

        try {
            if (!propertyFile.exists()) {
                logger.debug("Trying to create file {}.", propertyFile);
                Files.createParentDirs(propertyFile);
                FileWriter fileWriter = new FileWriter(propertyFile);

                p.setProperty("rollingCode", "0000");
                p.setProperty("address", String.format("%06X", getNewAddressForShutter()));
                p.store(fileWriter, "Initialized fields");
                fileWriter.close();
                logger.info("Created new property file {}", propertyFile);
            } else {
                FileReader fileReader = new FileReader(propertyFile);
                p.load(fileReader);
                fileReader.close();
                logger.info("Read properties from file {}", propertyFile);
            }
        } catch (IOException e) {
            logger.error("Error occured on writing the property file.", e);
        }
        return p;
    }

    /**
     * Calculates a new address for the shutter. Therefore all property files are read and a new address is calculated.
     *
     * @return New 6-digit address for the shutter
     * @throws IOException
     */
    private long getNewAddressForShutter() throws IOException {
        File directory = propertyFile.getParentFile();
        long maxAddress = 0;
        for (File file : directory.listFiles()) {
            if (FilenameUtils.getExtension(file.getName()).equals("properties") && !file.equals(propertyFile)) {
                logger.info("Parsing properties from file {}", file);
                FileReader fileReader = new FileReader(file);
                Properties other = new Properties();
                other.load(fileReader);
                long currentAddress = Long.decode("0x" + other.getProperty("address"));
                if (currentAddress > maxAddress) {
                    maxAddress = currentAddress;
                }
                fileReader.close();
            }
        }
        logger.info("Current max address is {}", maxAddress);
        return maxAddress + 1;
    }
}
