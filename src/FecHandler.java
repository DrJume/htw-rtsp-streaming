import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Jörg Vogt
 * @version 1.0
 */

/*
   Information according to RFC 5109
   http://apidocs.jitsi.org/libjitsi/
*/

public class FecHandler {
    // *** RTP-Header ************************
    static final int MJPEG = 26;
    // *** FEC Parameters -> Sender ************
    static final int maxGroupSize = 48;
    RTPpacket rtp;
    FECpacket fec;
    // Receiver
    HashMap<Integer, FECpacket> fecStack = new HashMap<>(); // list of fec packets
    HashMap<Integer, Integer> fecNr = new HashMap<>(); // Snr of corresponding fec packet
    HashMap<Integer, List<Integer>> fecList = new HashMap<>(); // list of involved media packets
    int playCounter = 0; // SNr of RTP-packet to play next, initialized with first received packet
    int FEC_PT = 127; // Type for FEC
    int fecSeqNr; // Sender: increased by one, starting from 0
    int lastReceivedSeqNr; // Receiver: last received media packet
    int fecGroupSize; // FEC group size
    int fecGroupCounter;

    // -> Receiver
    boolean useFec;

    // Error Concealment
    byte[] lastPayload = {1};

    // *** Statistics for media packets ********
    int nrReceived; // count only media at receiver
    int nrLost; // media missing, play loop
    int nrCorrected; // play loop
    int nrNotCorrected; // play loop
    int nrFramesRequested; // Video Frame
    int nrFramesLost; // Video Frame

    /**
     * Constructor for Sender
     */
    public FecHandler(int size) {
        fecGroupSize = size;
    }

    /**
     * Client can choose using FEC or not
     *
     * @param useFec choose of using FEC
     */
    public FecHandler(boolean useFec) {
        this.useFec = useFec;
    }

    // *************** Sender SET *******************************************************************

    /**
     * *** Sender *** Saves the involved RTP packets to build the FEC packet
     *
     * @param rtp RTPpacket
     */
    public void setRtp(RTPpacket rtp) {
        // init new FEC packet if necessary
        if (fec == null) {
            fec =
                    new FECpacket(
                            FEC_PT, fecSeqNr, rtp.gettimestamp(), fecGroupSize, rtp.getsequencenumber());
            fec.setUlpLevelHeader(0, 0, fecGroupSize);
        }

        fecGroupCounter++; // count the packets in the group
        fec.TimeStamp = rtp.gettimestamp(); // adjust the time stamp to the last packet in the group
        fec.addRtp(rtp);
    }

    /**
     * @return True, if all RTP-packets of the group are handled
     */
    public boolean isReady() {
        return (fecGroupCounter == fecGroupSize);
    }

    /**
     * *** Sender *** Builds the FEC-RTP-Packet and resets the FEC-group
     *
     * @return Bitstream of FEC-Packet including RTP-Header
     */
    public byte[] getPacket() {
        // fec.printHeaders();
        // Adjust and reset all involved variables
        fecSeqNr++;
        fecGroupCounter = 0;
        byte[] buf = fec.getpacket();
        fec = null; // reset fec
        return buf;
    }

    /**
     * Reset of fec group and variables
     */
    private void clearSendGroup() {
        // egalTODO
    }

    /**
     * *** Sender *** Posibility to set the group at run time
     *
     * @param size FEC Group
     */
    public void setFecGroupSize(int size) {
        fecGroupSize = size;
    }

    // *************** Receiver PUT *****************************************************************

    /**
     * Handles and store a recieved FEC packet
     *
     * @param rtp the received FEC-RTP
     */
    public void rcvFecPacket(RTPpacket rtp) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        // build fec from rtp
        fec = new FECpacket(rtp.getpacket(), rtp.getpacket().length);
        // DoneTASK remove comment for debugging
        // fec.printHeaders();

        // stores fec
        int seqNrFec = fec.getsequencenumber();
        fecSeqNr = seqNrFec; // for deletion of fec storage
        fecStack.put(seqNrFec, fec);

        // get RTP List
        ArrayList<Integer> list = fec.getRtpList();
        logger.log(Level.FINER, "FEC: set list: " + seqNrFec + " " + list.toString());

        // set list to get fec packet nr
        list.forEach((E) -> fecNr.put(E, seqNrFec)); // FEC-packet
        list.forEach((E) -> fecList.put(E, list));  // list of corresponding RTP packets
    }

    // *************** Receiver GET *****************************************************************

    /**
     * Checks if the RTP packet is reparable
     *
     * @param nr Sequence Nr.
     * @return true if possible
     */
    public boolean checkCorrection(int nr, HashMap<Integer, RTPpacket> mediaPackets) {
        FECpacket fecPacketForSn = fecStack.get(fecNr.get(nr));
        if (fecPacketForSn == null) return false;
        System.out.println("checkCorrection: FEC packet found for seqNr: " + nr);

        List<Integer> rtpSeqNrGroup = fecList.get(nr);
        if (rtpSeqNrGroup == null) return false;
        System.out.println("checkCorrection: rtpSeqNrGroup: " + rtpSeqNrGroup.toString());
        rtpSeqNrGroup.removeIf(seqNr -> seqNr == nr);
        if (rtpSeqNrGroup.isEmpty()) return false;
        System.out.println("checkCorrection: rtpSeqNrGroupRest: " + rtpSeqNrGroup.toString());

        System.out.println("checkCorrection: mediaPackets.keySet(): " + mediaPackets.keySet().toString());

        return mediaPackets.keySet().containsAll(rtpSeqNrGroup);
    }

    /**
     * Build an RTP packet from FEC and group
     *
     * @param nr Sequence Nr.
     * @return RTP packet
     */
    public RTPpacket correctRtp(int nr, HashMap<Integer, RTPpacket> mediaPackets) {
        //DoneTASK complete this method!
        Integer fecPacketNumber = fecNr.get(nr);
        FECpacket fecPacket = fecStack.get(fecPacketNumber);

        List<Integer> rtpSeqNrGroup = fecList.get(nr);
        rtpSeqNrGroup.removeIf((seqNr) -> seqNr == nr);

        for (int rtpSeqNr : rtpSeqNrGroup) {
//            System.out.println("correctRtp: mediaPacket: " + mediaPackets.get(rtpSeqNr));
            fecPacket.addRtp(mediaPackets.get(rtpSeqNr));
        }

        return fecPacket.getLostRtp(nr);
    }

    /**
     * It is necessary to clear all data structures
     *
     * @param nr Media Sequence Nr.
     */
    private void clearStack(int nr) {
        fecStack.clear();
        fecList.remove(nr);
    }

    // *************** Receiver Statistics ***********************************************************

    /**
     * @return Latest (highest) received sequence number
     */
    public int getSeqNr() {
        return lastReceivedSeqNr;
    }

    /**
     * @return Amount of received media packets (stored in jitter buffer)
     */
    public int getNrReceived() {
        return nrReceived;
    }

    /**
     * @return RTP-Snr of actual frame
     */
    public int getPlayCounter() {
        return playCounter;
    }

    /**
     * @return Number of lost media packets (calculated at time of display)
     */
    public int getNrLost() {
        return nrLost;
    }

    /**
     * @return number of corrected media packets
     */
    public int getNrCorrected() {
        return nrCorrected;
    }

    /**
     * @return Number of nor correctable media packets
     */
    public int getNrNotCorrected() {
        return nrNotCorrected;
    }

    /**
     * @return Number of requested but lost Video frames
     */
    public int getNrFramesLost() {
        return nrFramesLost;
    }

    /**
     * @return Number of requested Video frames
     */
    public int getNrFramesRequested() {
        return nrFramesRequested;
    }
}
