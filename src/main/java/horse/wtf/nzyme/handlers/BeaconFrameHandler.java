package horse.wtf.nzyme.handlers;

import horse.wtf.nzyme.*;
import horse.wtf.nzyme.dot11.Dot11ManagementFrame;
import horse.wtf.nzyme.dot11.Dot11MetaInformation;
import horse.wtf.nzyme.graylog.GraylogFieldNames;
import horse.wtf.nzyme.graylog.Notification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.util.ByteArrays;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

public class BeaconFrameHandler extends FrameHandler {

    // TODO kek this should really be handled in the Dot11BeaconPacket at some point
    private static final int SSID_LENGTH_POSITION = 37;
    private static final int SSID_POSITION = 38;

    private final AtomicInteger sampleCount;

    private static final Logger LOG = LogManager.getLogger(BeaconFrameHandler.class);

    public BeaconFrameHandler(Nzyme nzyme) {
        super(nzyme);

        this.sampleCount = new AtomicInteger(0);
    }

    @Override
    public void handle(byte[] payload, Dot11MetaInformation meta) throws IllegalRawDataException {
        tick();
        if(nzyme.getConfiguration().getBeaconSamplingRate() != 0) { // skip this completely if sampling is disabled
            if (sampleCount.getAndIncrement() == nzyme.getConfiguration().getBeaconSamplingRate()) {
                sampleCount.set(0);
            } else {
                return;
            }
        }

        // MAC header: 24 byte
        // Fixed parameters: 12 byte
        // Tagged parameters start at: 36 byte

        Dot11ManagementFrame beacon = Dot11ManagementFrame.newPacket(payload, 0, payload.length);

        // Check bounds for SSID length field.
        try {
            ByteArrays.validateBounds(payload, 0, SSID_LENGTH_POSITION+1);
        } catch(Exception e) {
            malformed();
            LOG.trace("Beacon payload out of bounds. (1) Ignoring.");
            return;
        }

        // SSID length.
        byte ssidLength = payload[SSID_LENGTH_POSITION];

        if(ssidLength < 0) {
            malformed();
            LOG.trace("Negative SSID length. Ignoring.");
            return;
        }

        // Check bounds for SSID field.
        try {
            ByteArrays.validateBounds(payload, SSID_POSITION, ssidLength);
        } catch(Exception e) {
            malformed();
            LOG.trace("Beacon payload out of bounds. (2) Ignoring.");
            return;
        }

        // Extract SSID
        byte[] ssidBytes = ByteArrays.getSubArray(payload, SSID_POSITION, ssidLength);

        // Check if the SSID is valid UTF-8 (might me malformed frame)
        if(!Tools.isValidUTF8(ssidBytes)) {
            malformed();
            LOG.trace("Beacon SSID not valid UTF8. Ignoring.");
            return;
        }

        String ssid = null;
        if(ssidLength >= 0) {
            ssid = new String(ssidBytes, Charset.forName("UTF-8"));
        }

        String transmitter = "";
        if(beacon.getHeader().getAddress2() != null) {
            transmitter = beacon.getHeader().getAddress2().toString();
        }

        String message;
        if (ssid != null && !ssid.trim().isEmpty()) {
            message = "Received beacon from " + transmitter + " for SSID " + ssid;
            nzyme.getStatistics().tickBeaconedNetwork(ssid);
        } else {
            // Broadcast beacon.
            message = "Received broadcast beacon from " + transmitter;
        }

        nzyme.getStatistics().tickAccessPoint(transmitter);

        nzyme.getGraylogUplink().notify(
                new Notification(message, nzyme.getChannelHopper().getCurrentChannel())
                        .addField(GraylogFieldNames.TRANSMITTER, transmitter)
                        .addField(GraylogFieldNames.SSID, ssid)
                        .addField(GraylogFieldNames.SUBTYPE, "beacon"),
                meta
        );

        LOG.debug(message);
    }

    @Override
    public String getName() {
        return "beacon";
    }

}