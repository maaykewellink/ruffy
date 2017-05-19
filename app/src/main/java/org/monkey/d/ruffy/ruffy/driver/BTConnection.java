package org.monkey.d.ruffy.ruffy.driver;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Created by SandraK82 on 15.05.17.
 */

public class BTConnection {
    private final BTHandler handler;
    private final Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private ListenThread listen;

    private BluetoothSocket currentConnection;

    private byte[] nonceRx = new byte[13];
    private byte[] nonceTx = new byte[13];

    private byte address;

    public Object pump_tf;
    public Object driver_tf;

    public int seqNo;
    private InputStream currentInput;
    private OutputStream currentOutput;
    private PairingRequest pairingReciever;
    private ConnectReciever connectReciever;

    public byte[] getNonceRx() {
        return nonceRx;
    }

    public byte[] getNonceTx() {
        return nonceTx;
    }

    public void setAndSaveAddress(byte address) {
        this.address = address;
        SharedPreferences prefs = activity.getSharedPreferences("pumpdata", Activity.MODE_PRIVATE);
        prefs.edit().putInt("address",address).commit();

    }

    public byte getAddress() {
        return address;
    }

    public BTConnection(final Activity activity, final BTHandler handler)
    {
        this.handler = handler;
        this.activity = activity;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, 1);
        }
        SharedPreferences prefs = activity.getSharedPreferences("pumpdata", Activity.MODE_PRIVATE);
        address = (byte)prefs.getInt("address",0);

        nonceTx = Utils.hexStringToByteArray(prefs.getString("nonceTx","00 00 00 00 00 00 00 00 00 00 00 00 00"));
    }

    public void makeDiscoverable() {
        resetRxNonce();
        resetTxNonce();

        IntentFilter filter = new IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST");
        pairingReciever = new PairingRequest(activity, handler);
        activity.registerReceiver(pairingReciever, filter);

        Intent discoverableIntent = new Intent("android.bluetooth.adapter.action.REQUEST_DISCOVERABLE");
        discoverableIntent.putExtra("android.bluetooth.adapter.extra.DISCOVERABLE_DURATION", 60);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        activity.startActivity(discoverableIntent);

        BluetoothServerSocket srvSock = null;
        try {
            srvSock = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("SerialLink", UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
        } catch (IOException e) {
            handler.fail("socket listen() failed");
            return;
        }

        final BluetoothServerSocket lSock = srvSock;
        listen = new ListenThread(srvSock);

        filter = new IntentFilter("android.bluetooth.device.action.ACL_CONNECTED");
        connectReciever = new ConnectReciever(handler);
        activity.registerReceiver(connectReciever, filter);

        listen.start();
    }

    public void stopDiscoverable() {
        if(listen!=null)
        {
            listen.halt();
        }
        if(bluetoothAdapter.isDiscovering())
        {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    public void connect(BluetoothDevice device) {
        connect(device.getAddress(), 4);
    }

    int state = 0;
    public void connect(String deviceAddress, int retry) {

        if(state!=0)
        {
            handler.log("in connect!");
            return;
        }
        state=1;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

        BluetoothSocket tmp = null;
        try {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            tmp = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
        } catch (IOException e) {
            handler.log("socket create() failed: "+e.getMessage());
        }
        if(tmp != null) {
            stopDiscoverable();
            activateConnection(tmp);
        }
        else
        {
            handler.log("failed the pump connection( retries left: "+retry+")");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(retry>0)
            {
                connect(deviceAddress,retry-1);
            }
            else
            {
                handler.fail("Failed to connect");
            }
        }
    }

    private void startReadThread() {
        new Thread() {
            @Override
            public void run() {
                try {
                    currentConnection.connect();
                    currentInput = currentConnection.getInputStream();
                    currentOutput = currentConnection.getOutputStream();
                } catch (IOException e) {
                    e.printStackTrace();
                    handler.fail("no connection possible");
                }
                try {
                    activity.unregisterReceiver(connectReciever);
                }catch(Exception e){/*ignore*/}
                try {
                    activity.unregisterReceiver(pairingReciever);
                }catch(Exception e){/*ignore*/}
                state=0;
                handler.deviceConnected();
                byte[] buffer = new byte[512];
                while (true) {
                    try {
                        int bytes = currentInput.read(buffer);
                        handler.log("read "+bytes+": "+Utils.bufferString(buffer,bytes));

                        //FIXME temp
                        handler.handleRawData(buffer,bytes);
                        //FIXME deesacape
                        //FIXME but into handler
                    } catch (Exception e) {
                        e.printStackTrace();
                        //do not fail here as we maybe just closed the socket..
                        handler.log("got error in read");
                        return;
                    }
                }
            }
        }.start();
    }

    public void writeCommand(byte[] key) {
        List<Byte> out = new LinkedList<Byte>();
        for(Byte b : key)
            out.add(b);
        for (Byte n : nonceTx)
            out.add(n);
        Utils.addCRC(out);

        List<Byte> temp = Frame.frameEscape(out);

        byte[] ro = new byte[temp.size()];
        int i = 0;
        for(byte b : temp)
            ro[i++]=b;

        StringBuilder sb = new StringBuilder();
        for (i = 0; i < key.length; i++) {
            sb.append(String.format("%02X ", key[i]));
        }
        handler.log("writing command: "+sb.toString());
        write(ro);
    }

    private void activateConnection(BluetoothSocket newConnection){
        if(this.currentConnection!=null)
        {
            try {
                this.currentOutput.close();
            } catch (Exception e) {/*ignore*/}
            try {
                this.currentInput.close();
            } catch (Exception e) {/*ignore*/}
            try {
                this.currentConnection.close();
            } catch (Exception e) {/*ignore*/}
            this.currentInput=null;
            this.currentOutput=null;
            this.currentConnection=null;
            handler.log("closed current Connection");
        }
        handler.log("got new Connection: "+newConnection);
        this.currentConnection = newConnection;
        if(newConnection!=null)
        {
            startReadThread();
        }
    }

    public void write(byte[] ro){
        if(this.currentConnection==null)
        {
            handler.fail("unable to write: no socket");
            return;
        }
        try {
            currentOutput.write(ro);
            handler.log("wrote "+ro.length+" bytes: "+Utils.bufferString(ro,ro.length));
        }catch(Exception e)
        {
            e.printStackTrace();
            handler.fail("failed write of "+ro.length+" bytes!");
        }
    }

    public void resetTxNonce() {
        for (int i = 0; i < nonceTx.length; i++)
            nonceTx[i] = 0;
        SharedPreferences prefs = activity.getSharedPreferences("pumpdata", Activity.MODE_PRIVATE);
        prefs.edit().putString("nonceTx",Utils.bufferString(nonceTx,nonceTx.length)).commit();
    }

    public void resetRxNonce() {
        for (int i = 0; i < nonceRx.length; i++)
            nonceRx[i] = 0;
    }

    public void incrementNonceTx() {
        Utils.incrementArray(nonceTx);
        SharedPreferences prefs = activity.getSharedPreferences("pumpdata", Activity.MODE_PRIVATE);
        prefs.edit().putString("nonceTx",Utils.bufferString(nonceTx,nonceTx.length)).commit();
    }

    public void log(String s) {
        if(handler!=null)
            handler.log(s);
    }
}
