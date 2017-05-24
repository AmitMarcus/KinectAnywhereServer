package org.kinectanywhereandroid.network;

import android.util.Log;

import org.kinectanywhereandroid.MainActivity;
import org.kinectanywhereandroid.framework.RemoteKinect;
import org.kinectanywhereandroid.model.Joint;
import org.kinectanywhereandroid.model.Skeleton;
import org.kinectanywhereandroid.util.DataHolder;
import org.kinectanywhereandroid.util.DataHolderEntry;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

// Good example: http://android-er.blogspot.co.il/2016/06/android-datagramudp-server-example.html

public class UdpServerThread extends Thread{
    private final static String TAG = "UDP_SERVER_THREAD";
    int serverPort;
    MainActivity mActivity;
    DatagramSocket socket;
    private Map<String, RemoteKinect> _kinectDict; // Mapping of connected clients

    boolean running;

    public UdpServerThread(int serverPort, MainActivity mActivity) {
        super();
        this.serverPort = serverPort;
        this.mActivity = mActivity;

        _kinectDict = new HashMap<>();
        DataHolder.INSTANCE.save(DataHolderEntry.CONNECTED_HOSTS, _kinectDict); // Share hosts list with rest of app modules
    }

    public void setRunning(boolean running){
        this.running = running;
    }

    private void updateState(final String state){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mActivity.updateState(state);
            }
        });
    }

    public List<Skeleton> parseSkeleton(final DatagramPacket packet, int i) {
        float[] points = new float[3];
        byte[] point = new byte[4];
        byte[] timestampsBytes = new byte[8];

        // Parse timestamp
        for (int k = 0; k < 8; k++) {   // Get current point from packet
            timestampsBytes[k] = packet.getData()[i + k];
        }

        i += 8;

        double timestamp = ByteBuffer.wrap(timestampsBytes).order(ByteOrder.LITTLE_ENDIAN).getDouble();

        List<Skeleton> skeletonList = new LinkedList<>();

        while (i < packet.getLength()) {
            Skeleton skeleton = new Skeleton();

            skeleton.timestamp = timestamp;

            // Parse skeletons tracker id
            for (int k = 0; k < 4; k++) {   // Get current point from packet
                point[k] = packet.getData()[i + k];
            }
            skeleton.trackingId = ByteBuffer.wrap(point).order(ByteOrder.LITTLE_ENDIAN).getInt();

            i += 4;

            // Parse joints
            while (i < packet.getLength()) {
                Joint joint = new Joint();

                joint.type = Joint.JointType.values()[packet.getData()[i++]]; // Get current type from packet
                joint.trackingState = Joint.JointTrackingState.values()[packet.getData()[i++]]; // Get current type from packet

                for (int j = 0; j < 3; j++) { // For every point in joint (x,y,z):
                    for (int k = 0; k < 4; k++) {   // Get current point from packet
                        point[k] = packet.getData()[i + k];
                    }

                    points[j] = ByteBuffer.wrap(point).order(ByteOrder.LITTLE_ENDIAN).getFloat(); // Convert current point to float
                    i += 4;
                }

                // Create joint from points
                joint.x = points[0];
                joint.y = points[1];
                joint.z = points[2];

                skeleton.joints[joint.type.getValue()] = joint;

                // Check for end of skeleton
                if (packet.getData()[i] == -1 && packet.getData()[i+1] == -1) {
                    i += 2;
                    break;
                }
            }

            skeletonList.add(skeleton);
        }

        return skeletonList;
    }

    @Override
    public void run() {

        running = true;

        try {
            updateState("Starting UDP Server");
            socket = new DatagramSocket(serverPort);

            updateState("UDP Server is running");
            Log.e(TAG, "UDP Server is running");


            while(running){
                byte[] buf = new byte[5000];

                // receive request
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);   //this code block the program flow

                // Parse timestamp
                byte[] hostnameBytes = new byte[30];
                int i = 0;
                while (packet.getData()[i] != 0) {
                    hostnameBytes[i] = packet.getData()[i];
                    i++;
                }

                String hostname = new String(Arrays.copyOfRange(hostnameBytes, 0, i), StandardCharsets.US_ASCII);

                if (_kinectDict.get(hostname) == null) {
                    _kinectDict.put(hostname, new RemoteKinect());
                }

                RemoteKinect remoteKinect = _kinectDict.get(hostname);
                remoteKinect.lastBeacon = System.currentTimeMillis();

                if (i < packet.getLength()) {
                    List<Skeleton> skeletonList = parseSkeleton(packet, ++i);
                    remoteKinect.skeletonQueue.add(skeletonList);
                }
            }

            Log.e(TAG, "UDP Server ended");

        } catch (SocketException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(socket != null){
                socket.close();
                Log.e(TAG, "socket.close()");
            }
        }
    }
}