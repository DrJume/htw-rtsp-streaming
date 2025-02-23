/* ------------------
Server
usage: java Server [RTSP listening port]
---------------------- */

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.Enumeration;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Server extends JFrame implements ActionListener, ChangeListener {

    final static int startGroupSize = 2;
    // RTSP variables
    // ----------------
    // rtsp states
    static final int INIT = 0;
    static final int READY = 1;
    static final int PLAYING = 2;
    // rtsp message types
    static final int SETUP = 3;
    static final int PLAY = 4;
    static final int PAUSE = 5;
    static final int TEARDOWN = 6;
    static final int OPTIONS = 7;
    static final int DESCRIBE = 8;
    static final String CRLF = "\r\n";
    static JLabel stateLabel;
    // Video variables:
    // ----------------
    static int imageNb = 0; // image nb of the image currently transmitted
    static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video
    static int DEFAULT_FRAME_PERIOD = 40; // Frame period of the video to stream, in ms
    static int state; // RTSP Server state == INIT or READY or PLAY
    // input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName = ""; // video file requested from the client
    // byte[] buf; // buffer used to store the images to send to the client
    static String VideoDir = "videos/";
    static int RTSP_ID = 123456; // ID of the RTSP session
    public VideoMetadata videoMeta = null;
    // RTP variables:
    // ----------------
    DatagramSocket RTPsocket; // socket to be used to send and receive UDP packets
    DatagramPacket senddp; // UDP packet containing the video frames
    InetAddress ClientIPAddr; // Client IP address
    int RTP_dest_port = 0; // destination port for RTP packets  (given by the RTSP Client)
    int FEC_dest_port = 0; // destination port for RTP-FEC packets  (RTP or RTP+2)
    RtpHandler rtpHandler = null;
    Random random = new Random(123456); // fixed seed for debugging
    int dropCounter; // Nr. of dropped media packets
    // GUI:
    // ----------------
    JLabel label;
    VideoReader video; // VideoStream object used to access video frames
    Timer timer; // timer used to send the images at the video frame rate
    Socket RTSPsocket; // socket used to send/receive RTSP messages
    int RTSPSeqNb = 0; // Sequence number of RTSP messages within the session
    String sdpTransportLine = "";
    // Channel errors
    private double lossRate = 0.0;
    private ButtonGroup encryptionButtons = null;


    public Server() {
        super("Server"); // init Frame
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

        // init RTP socket and FEC
        // theServer.RTPsocket = new DatagramSocket();
        rtpHandler = new RtpHandler(startGroupSize);

        // Handler to close the main window
        addWindowListener(
                new WindowAdapter() {
                    public void windowClosing(WindowEvent e) {
                        // stop the timer and exit
                        if (timer != null) timer.stop();
                        System.exit(0);
                    }
                });

        // GUI:
        label = new JLabel("Send frame #        ", JLabel.CENTER);
        stateLabel = new JLabel("State:         ", JLabel.CENTER);
        getContentPane().add(label, BorderLayout.NORTH);
        getContentPane().add(stateLabel, BorderLayout.SOUTH);
        // Error Slider
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        JSlider dropRate = new JSlider(JSlider.HORIZONTAL, 0, 100, 0);
        dropRate.addChangeListener(this);
        dropRate.setMajorTickSpacing(10);
        dropRate.setMinorTickSpacing(5);
        dropRate.setPaintTicks(true);
        dropRate.setPaintLabels(true);
        dropRate.setName("p");

        JSlider groupSize = new JSlider(JSlider.HORIZONTAL, 2, 48, startGroupSize);
        groupSize.addChangeListener(this::stateChanged);
        groupSize.setMajorTickSpacing(4);
        groupSize.setMinorTickSpacing(1);
        groupSize.setPaintLabels(true);
        groupSize.setPaintTicks(true);
        groupSize.setName("k");
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 4;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        mainPanel.add(groupSize, gbc);
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 4;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        mainPanel.add(dropRate, gbc);

        initGuiEncryption(mainPanel);

        getContentPane().add(mainPanel, BorderLayout.CENTER);

        try {
            RTPsocket = new DatagramSocket();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception caught: " + e);
        }
    }

    // ------------------------------------
    // main
    // ------------------------------------
    public static void main(String[] argv) throws Exception {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        CustomLoggingHandler.prepareLogger(logger);
        /* set logging level
         * Level.CONFIG: default information (incl. RTSP requests)
         * Level.ALL: debugging information (headers, received packages and so on)
         */
        logger.setLevel(Level.ALL);

        // create a Server object
        Server theServer = new Server();
        theServer.setSize(500, 200);
        theServer.setVisible(true);

        // get RTSP socket port from the command line
        int RTSPport = Integer.parseInt(argv[0]);

        // Initiate TCP connection with the client for the RTSP session
        ServerSocket listenSocket = new ServerSocket(RTSPport);
        theServer.RTSPsocket = listenSocket.accept();
        listenSocket.close();

        // Get Client IP address
        theServer.ClientIPAddr = theServer.RTSPsocket.getInetAddress();

        // Initiate RTSPstate
        state = INIT;
        stateLabel.setText("INIT");


        // Set input and output stream filters:
        RTSPBufferedReader =
                new BufferedReader(new InputStreamReader(theServer.RTSPsocket.getInputStream()));
        RTSPBufferedWriter =
                new BufferedWriter(new OutputStreamWriter(theServer.RTSPsocket.getOutputStream()));


        int request_type;

        // loop to handle RTSP requests
        while (true) {
            // parse the request
            request_type = theServer.parse_RTSP_request(); // blocking

            switch (request_type) {
                case SETUP:
                    // Wait for the SETUP message from the client
                    state = READY;
                    stateLabel.setText("READY");
                    logger.log(Level.INFO, "New RTSP state: READY");

                    if (theServer.videoMeta == null) {
                        theServer.videoMeta = Server.getVideoMetadata(VideoFileName);
                    }

                    // init Timer
                    theServer.timer = new Timer(1000 / theServer.videoMeta.getFramerate(), theServer);
                    theServer.timer.setInitialDelay(0);
                    theServer.timer.setCoalesce(false); // Coalesce can lead to buffer underflow in client

                    // Send response
                    theServer.send_RTSP_response(SETUP);

                    // init the VideoStream object:
                    theServer.video = new VideoReader(VideoFileName);
                    imageNb = 0;

                    break;

                case PLAY:
                    if (state == READY) {
                        // send back response
                        theServer.send_RTSP_response(PLAY);
                        // start timer
                        theServer.timer.start();
                        // update state
                        state = PLAYING;
                        stateLabel.setText("PLAY");
                        logger.log(Level.INFO, "New RTSP state: PLAYING");
                    }
                    break;

                case PAUSE:
                    if (state == PLAYING) {
                        // send back response
                        theServer.send_RTSP_response(PAUSE);
                        // stop timer
                        theServer.timer.stop();
                        // update state
                        state = READY;
                        stateLabel.setText("READY");
                        logger.log(Level.INFO, "New RTSP state: READY");
                    }
                    break;

                case TEARDOWN:
                    state = INIT;
                    stateLabel.setText("INIT");
                    // send back response
                    theServer.send_RTSP_response(TEARDOWN);
                    // stop timer
                    theServer.timer.stop();
                    theServer.videoMeta = null;
                    // close sockets
                    // theServer.RTSPsocket.close();
                    // theServer.RTPsocket.close();
                    break;

                case OPTIONS:
                    logger.log(Level.INFO, "Options request");
                    theServer.send_RTSP_response(OPTIONS);
                    break;

                case DESCRIBE:
                    logger.log(Level.INFO, "DESCRIBE Request");
                    theServer.send_RTSP_response(DESCRIBE);
                    break;

                default:
                    logger.log(Level.WARNING, "Wrong request");
            }
        }
    }

    /**
     * Get the metadata from a video file.
     * <p>
     * If no metadata is available, all fields are zero-initialized with
     * exception of the framerate. Because the framerate is strongly required,
     * it is set to a default value.
     *
     * @param filename Name of the video file
     * @return metadata structure containing the extracted information
     */
    private static VideoMetadata getVideoMetadata(String filename) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        VideoMetadata meta = null;

        String[] splitFilename = filename.split("\\.");
        switch (splitFilename[splitFilename.length - 1]) {
            case "avi":
                meta = AviMetadataParser.parse(filename);
                break;
            case "mov":
                meta = QuickTimeMetadataParser.parse(filename);
                break;
            default:
                logger.log(Level.WARNING, "File extension not recognized: " + filename);
            case "mjpg":
            case "mjpeg":
                meta = new VideoMetadata(1000 / DEFAULT_FRAME_PERIOD);
                break;
        }

        assert meta != null : "VideoMetadata of file " + filename + " was not initialized correctly";
        return meta;
    }

    /**
     * Handler for Channel error Slider
     *
     * @param e Change Event
     */
    public void stateChanged(ChangeEvent e) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        JSlider source = (JSlider) e.getSource();
        if (!source.getValueIsAdjusting()) {
            if (source.getName().equals("k")) {
                int k = source.getValue();
                rtpHandler.setFecGroupSize(k);
                logger.log(Level.INFO, "New Group size: " + k);
            } else {
                lossRate = source.getValue();
                lossRate = lossRate / 100;
                logger.log(Level.INFO, "New packet error rate: " + lossRate);
            }
        }
    }

    /**
     * Handler for encryption RadioButtons.
     * <p>
     * The ItemEvent is just fired if a Button is selected
     * which previous was not.
     *
     * @param ev ItemEvent
     */
    public void radioButtonSelected(ItemEvent ev) {
        JRadioButton rb = (JRadioButton) ev.getItem();
        if (rb.isSelected()) {
            String label = rb.getText();
            RtpHandler.EncryptionMode mode;

            mode = switch (label) {
                case "SRTP" -> RtpHandler.EncryptionMode.SRTP;
                case "JPEG" -> RtpHandler.EncryptionMode.JPEG;
                default -> RtpHandler.EncryptionMode.NONE;
            };

            boolean encryptionSet = rtpHandler.setEncryption(mode);
            if (!encryptionSet) {
                Enumeration<AbstractButton> buttons = encryptionButtons.getElements();
                while (buttons.hasMoreElements()) {
                    AbstractButton ab = buttons.nextElement();
                    if (ab.getText().equals("keine")) {
                        ab.setSelected(true);
                    }
                }
            }
        }
    }

    /**
     * Hander for timer
     *
     * @param e ActionEvent
     */
    public void actionPerformed(ActionEvent e) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        imageNb++; // image counter
        byte[] packet_bits;

        try {
            byte[] frame = video.readNextImage(); // get next frame
            if (frame != null) {
                logger.log(Level.FINE, "Frame size: " + frame.length);

                packet_bits = rtpHandler.jpegToRtpPacket(frame, videoMeta.getFramerate());

                // send the packet as a DatagramPacket over the UDP socket
                senddp = new DatagramPacket(packet_bits, packet_bits.length, ClientIPAddr, RTP_dest_port);

                sendPacketWithError(senddp, false); // Send with packet loss

                if (rtpHandler.isFecPacketAvailable()) {
                    logger.log(Level.FINE, "FEC-Encoder ready...");
                    byte[] fecPacket = rtpHandler.createFecPacket();
                    // send to the FEC dest_port
                    senddp = new DatagramPacket(fecPacket, fecPacket.length, ClientIPAddr, FEC_dest_port);
                    sendPacketWithError(senddp, true);
                }

                // update GUI
                label.setText("Send frame #" + imageNb);
            } else timer.stop();
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Exception caught: " + ex);
            ex.printStackTrace();
            System.exit(0);
        }
    }

    /**
     * @param senddp Datagram to send
     * @throws Exception Throws all
     */
    private void sendPacketWithError(DatagramPacket senddp, boolean fec) throws Exception {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        String label;
        if (fec) label = " fec ";
        else label = " media ";
        // DoneTASK correct the if-instruction to work properly
        if (random.nextDouble() > lossRate) {
            logger.log(Level.FINE, "Send frame: " + imageNb + label);
            RTPsocket.send(senddp);
        } else {
            System.err.println("Dropped frame: " + imageNb + label);
            if (!fec) dropCounter++;
        }
        // System.out.println("Drop count media packets: " +  dropCounter);
    }

    /**
     * Parse RTSP-Request
     *
     * @return RTSP-Request Type (SETUP, PLAY, etc.)
     */
    private int parse_RTSP_request() {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        int request_type = -1;
        try {
            logger.log(Level.INFO, "*** wait for RTSP-Request ***");
            // parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Server - Received from Client:");
            logger.log(Level.CONFIG, RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            // convert to request_type structure:
            request_type = switch ((request_type_string)) {
                case "SETUP" -> SETUP;
                case "PLAY" -> PLAY;
                case "PAUSE" -> PAUSE;
                case "TEARDOWN" -> TEARDOWN;
                case "OPTIONS" -> OPTIONS;
                case "DESCRIBE" -> DESCRIBE;
                default -> -1;
            };

            if (request_type == SETUP
                    || request_type == DESCRIBE) {
                // extract VideoFileName from RequestLine
                String dir = tokens.nextToken();
                //String[] tok = dir.split(".+?/(?=[^/]+$)");
                String[] tok = dir.split("/");
                //VideoFileName = VideoDir + tok[1];
                VideoFileName = VideoDir + tok[3];
                logger.log(Level.CONFIG, "File: " + VideoFileName);
            }

            String line = "";
            line = RTSPBufferedReader.readLine();
            while (!line.equals("")) {
                logger.log(Level.FINE, line);
                if (line.contains("CSeq")) {
                    tokens = new StringTokenizer(line);
                    tokens.nextToken();
                    RTSPSeqNb = Integer.parseInt(tokens.nextToken());
                } else if (line.contains("Transport")) {
                    sdpTransportLine = line;
                    RTP_dest_port = Integer.parseInt(line.split("=")[1].split("-")[0]);
                    FEC_dest_port = RTP_dest_port;
                    logger.log(Level.FINE, "Client-Port: " + RTP_dest_port);
                }
                // else is any other field, not checking for now

                line = RTSPBufferedReader.readLine();
            }

            logger.log(Level.INFO, "*** Request received ***\n");

        } catch (Exception ex) {
            ex.printStackTrace();
            logger.log(Level.SEVERE, "Exception caught: " + ex);
            System.exit(0);
        }
        return (request_type);
    }

    /**
     * Send RTSP Response
     *
     * @param method RTSP-Method
     */
    private void send_RTSP_response(int method) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        logger.log(Level.INFO, "*** send RTSP-Response ***");
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            // 3th line depends on Request
            switch (method) {
                case OPTIONS:
                    RTSPBufferedWriter.write(options());
                    break;
                case DESCRIBE:
                    RTSPBufferedWriter.write(describe());
                    break;
                case SETUP:
                    RTSPBufferedWriter.write(sdpTransportLine + ";server_port=");
                    RTSPBufferedWriter.write(RTPsocket.getLocalPort() + "-");
                    RTSPBufferedWriter.write((RTPsocket.getLocalPort() + 1) + "" + CRLF);
                    // RTSPBufferedWriter.write(";ssrc=0;mode=play" + CRLF);
                default:
                    RTSPBufferedWriter.write("Session: " + RTSP_ID + ";timeout=30000" + CRLF);
                    break;
            }

            // Send end of response
            if (method != DESCRIBE) RTSPBufferedWriter.write(CRLF);

            RTSPBufferedWriter.flush();
            logger.log(Level.FINE, "*** RTSP-Server - Sent response to Client ***");

        } catch (Exception ex) {
            ex.printStackTrace();
            logger.log(Level.SEVERE, "Exception caught: " + ex);
            System.exit(0);
        }
    }

    /**
     * Creates a OPTIONS response string
     *
     * @return Options string, starting with: Public: ...
     */
    // DoneTASK Complete the OPTIONS response
    private String options() {
        return "Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE" + CRLF + CRLF;
    }

    /**
     * Creates a DESCRIBE response string in SDP format for current media
     */
    // DoneTASK Complete the DESCRIBE response
    private String describe() {
        StringWriter rtspHeader = new StringWriter();
        StringWriter rtspBody = new StringWriter();
        VideoMetadata meta = Server.getVideoMetadata(VideoFileName);

        // Write the body first, so we can get the size later
        rtspBody.write("v=0" + CRLF);
        rtspBody.write("o=- " + RTSP_ID + " " + RTSP_ID + " IN IP4 localhost" + CRLF);
        rtspBody.write("s=HTW Video" + CRLF);
        rtspBody.write("c=IN IP4 localhost" + CRLF);
        rtspBody.write("t=0 0" + CRLF);
        rtspBody.write("m=video 8554 RTP/AVP 26" + CRLF);
        rtspBody.write("a=rtpmap:26 JPEG/90000" + CRLF);
        rtspBody.write("a=framerate:" + meta.getFramerate() + CRLF);

        rtspHeader.write("Content-Base: " + "rtsp://localhost:8554/htw.mjpeg" + CRLF);
        rtspHeader.write("Content-Type: " + "application/sdp" + CRLF);
        rtspHeader.write("Content-Length: " + rtspBody.toString().getBytes().length + CRLF);
        rtspHeader.write(CRLF);

        return rtspHeader.toString() + rtspBody.toString();
    }

    private void initGuiEncryption(JPanel panel) {
        GridBagConstraints gbc = new GridBagConstraints();
        JLabel encryptionLabel = new JLabel("Verschlüsselung:");
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 10, 0, 0);
        panel.add(encryptionLabel, gbc);

        encryptionButtons = new ButtonGroup();
        JRadioButton e_none = new JRadioButton("keine");
        e_none.addItemListener(this::radioButtonSelected);
        encryptionButtons.add(e_none);
        e_none.setSelected(true);
        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(e_none, gbc);

        JRadioButton e_srtp = new JRadioButton("SRTP");
        e_srtp.addItemListener(this::radioButtonSelected);
        encryptionButtons.add(e_srtp);
        gbc = new GridBagConstraints();
        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(e_srtp, gbc);

        JRadioButton e_jpeg = new JRadioButton("JPEG");
        e_jpeg.addItemListener(this::radioButtonSelected);
        encryptionButtons.add(e_jpeg);
        gbc = new GridBagConstraints();
        gbc.gridx = 3;
        gbc.gridy = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(e_jpeg, gbc);
    }
}
