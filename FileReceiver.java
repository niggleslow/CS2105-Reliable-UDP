/*

Author: Nicholas Low Jun Han (A0110574N)
Assignment 2: Reliable File Transfer Protocol

This proxy is coded to meet the following criteria as specified in the PDF:

BASIC CRITERIA:

1. Can be compiled on sunfire.
2. Can successfully transfer a file from sender to receiver over a perfectly reliable channel (No error is introduced)
3. Can successfully transfer a file from sender to receiver (With packet corruption)
4. Can successfully transfer a file from sender to receiver (With presence of packet loss)
5. Can successfully transfer a file from sender to receiver (With both packet corruption and packet loss)

ADVANCED CRITERIA

1. Can successfully transfer a file from sender to receiver (With reordering of packets)
2. Can successfully transfer a file from sender to receiver (With combination of packet corruption, packet loss and reordering)

Project Implementation Phases:

1. Sending a file from sender to receiver

2. Sending a file from sender to receiver with corrupt packets

3. Sending a file from sender to receiver with dropped packets

4. Sending a file from sender to receiver with both errors

Packet structure:

<DATA>
[ CHECKSUM (0-7) | SEQUENCE NUMBER (8-11) | PACKET_TYPE (12-15) | DATA_LENGTH (16-20) | CONTENT (21 - 999) ]

<REPLY [NAK & ACK]>
[ CHECKSUM (0-7) | SEQUENCE NUMBER (8-11) | STATUS NUMBER : ACK(1)|NAK(2) (12-15)]

*/

import java.net.*;
import java.util.*;
import java.nio.*;
import java.util.zip.*;
import java.io.*;

public class FileReceiver {

	//CONSTANT PACKET SIZES
	public static final int SIZE_PACKET = 1000;
	public static final int SIZE_REPLY = 16;

	//Indices of the tags on a UDP packet
	public static final int INDEX_CHECKSUM = 0; //0-7 checksum index
	public static final int INDEX_SEQ = 8; //8-11 seq index  
	public static final int INDEX_TYPE = 12; //12-15 type index
	public static final int INDEX_LENGTH = 16; //16-20 data length index
	public static final int INDEX_CONTENT = 20; //8-15 checksum index

	//CONSTANT FINAL VALUES
	public static final int PACKET_NAME = 1;
	public static final int PACKET_DATA = 2;
	public static final int ACK = 1;
	public static final int NAK = 2;

	//VARIABLES USED IN CLASS
	public static int receivingPort;
	public static CRC32 checksum;
	private static BufferedOutputStream fileOutStream;
	private static int currentSeq = 0;
	private static String outFileName;

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: FileReceiver <port>");
			System.exit(-1);
		}

		//Creating receiving socket
		receivingPort = Integer.parseInt(args[0]);
		DatagramSocket sk = new DatagramSocket(receivingPort);
		
		//Creating datagram packet definition
		byte[] data = new byte[SIZE_PACKET];
		DatagramPacket pkt = new DatagramPacket(data, data.length);
		ByteBuffer b = ByteBuffer.wrap(data);
		
		//Creating CRC32 object
		CRC32 crc = new CRC32();
		
		//Main loop
		while(true)	{

			//Create reply byte[]
			byte[] reply = new byte[16];

			//Receiving incoming datagram packet
			pkt.setLength(data.length);
			sk.receive(pkt);

			//Error that packet is too short
			if(pkt.getLength() < 20) {
				//System.out.println("Pkt too short!");
				continue;
			}

			//Checksum validification
			if(!checkChkSum(b, data)) { //Packet corrupted, send NAK
				//System.out.println("Packet received is corrupted, sending NAK");
				reply = createNAK(currentSeq);
				DatagramPacket nak = new DatagramPacket(reply, reply.length, pkt.getSocketAddress());
				//System.out.println("Sending NAK for " + currentSeq);
				sk.send(nak);
			}
			else { //Packet verified, continue
				//System.out.println("Packet received is verified, continue processing...");
				int packetSeqNumber = b.getInt(INDEX_SEQ);
				int dataLength = b.getInt(INDEX_LENGTH);
				
				if(packetSeqNumber == currentSeq) { //correct packet received!
					//System.out.println("Packet received: " + packetSeqNumber + " == Current Sequence Number: " + currentSeq);
					
					if(packetSeqNumber == 0) { //file name packet received
						outFileName = extractFileName(data, dataLength).trim();
						File outputFile = new File(outFileName);
						
						if(!outputFile.exists()) {
							outputFile.createNewFile();
						}
						fileOutStream = new BufferedOutputStream(new FileOutputStream(outputFile));
						//System.out.println("Output file name is: " + outFileName + " ; File output stream has been established");
					}
					else { //data packet received
						byte[] toBeWritten = new byte[dataLength];
						toBeWritten = extractData(data, dataLength);
						//System.out.println("Writing " + dataLength + " bytes into " + outFileName);
						fileOutStream.write(toBeWritten);
						fileOutStream.flush();
					}

					reply = createACK(currentSeq);
					DatagramPacket ack = new DatagramPacket(reply, reply.length, pkt.getSocketAddress());
					//System.out.println("Sending ACK packet for sequence number " + packetSeqNumber);
					sk.send(ack);

					currentSeq++;
				} 
				else if(packetSeqNumber == currentSeq - 1) { //CASE: ACK for previous packet dropped -> resend ACK for previous packet
					reply = createACK(packetSeqNumber);
					DatagramPacket ack = new DatagramPacket(reply, reply.length, pkt.getSocketAddress());
					//System.out.println("Resending ACK packet for sequence number " + packetSeqNumber);
					sk.send(ack);
				}
				else {
					reply = createNAK(currentSeq);
					DatagramPacket nak = new DatagramPacket(reply, reply.length, pkt.getSocketAddress());
					//System.out.println("Sending NAK packet for sequence number " + currentSeq);
				}
			}
		}
	}

	private static String extractFileName(byte[] data, int dataLength) throws Exception {
		byte[] temp = new byte[dataLength];
		for(int i = 0; i < dataLength; i++) {
			temp[i] = data[INDEX_CONTENT + i];
		}
		return new String(temp, "UTF-8"); 
	} 

	public static byte[] extractData(byte[] data, int dataLength) throws Exception {
		byte[] dataToWrite = new byte[dataLength];
		for(int i = 0; i < dataToWrite.length; i++) {
			dataToWrite[i] = data[INDEX_CONTENT + i];
		}
		return dataToWrite;
	}

	private static int getSeqNo(ByteBuffer b) {
		b.rewind();
		int seqNo = b.getInt(INDEX_SEQ);
		return seqNo;
	}

	private static int getPacketType(ByteBuffer b) {
		b.rewind();
		int pktType = b.getInt(INDEX_TYPE);
		return pktType;
	}

	private static int getDataLength(ByteBuffer b) {
		b.rewind();
		int dataLength = b.getInt(INDEX_LENGTH);
		return dataLength;
	}

	public static boolean checkChkSum(ByteBuffer b, byte[] data) {
		//Obtaining CRC32 value as per header
		b.rewind();
		long chksum = b.getLong(INDEX_CHECKSUM);

		//Calculating CRC32 value as per data
		checksum = new CRC32();
		checksum.reset();
		checksum.update(data, INDEX_SEQ, data.length-INDEX_SEQ);

		// Debug output
		// //System.out.println("Received CRC:" + crc.getValue() + " Data:" + bytesToHex(data, pkt.getLength()));

		//If CRC32 Header != CRC32 Data, data is corrupt
		if (checksum.getValue() != chksum) {
			return false;
		} else {
			return true;
		}
	}

	private static byte[] createACK(int seq) {
		//Creating byte[] and ByteBuffer
		byte[] ack = new byte[SIZE_REPLY];
		ByteBuffer b = ByteBuffer.wrap(ack);
		
		//Add seq number and ACK to ack[]
		b.clear();
		b.putLong(0);
		b.putInt(seq); //add sequence number 
		b.putInt(ACK);

		//Create checksum and add it to ack[]
		checksum = new CRC32();
		checksum.reset();
		checksum.update(ack, INDEX_SEQ, ack.length - INDEX_SEQ);
		b.rewind();
		long chksum = checksum.getValue();
		b.putLong(chksum);

		return ack;
	}

	private static byte[] createNAK(int seq) {
				//Creating byte[] and ByteBuffer
		byte[] nak = new byte[SIZE_REPLY];
		ByteBuffer b = ByteBuffer.wrap(nak);
		
		//Add seq number and ACK to ack[]
		b.clear();
		b.putLong(0);
		b.putInt(seq); //add sequence number 
		b.putInt(NAK);

		//Create checksum and add it to ack[]
		checksum = new CRC32();
		checksum.reset();
		checksum.update(nak, INDEX_SEQ, nak.length - INDEX_SEQ);
		b.rewind();
		long chksum = checksum.getValue();
		b.putLong(chksum);

		return nak;
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	
	public static String bytesToHex(byte[] bytes, int len) {
	    char[] hexChars = new char[len * 2];
	    for ( int j = 0; j < len; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
}
