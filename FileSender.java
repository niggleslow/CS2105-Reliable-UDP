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

REFERENCES:
-Setting timeout for socket (Source: http://stackoverflow.com/questions/12363078/adding-timeout-to-datagramsocket-receive)

*/

import java.net.*;
import java.util.*;
import java.nio.*;
import java.util.zip.*;
import java.io.*;

public class FileSender {

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

	//CONSTANTS FROM ARGS
	private static InetAddress host;
	private static int port;
	private static String fileName;
	private static String outputFileName;

	//VARIABLES USED IN CLASS
	private static int seqNumber = 0;
	private static boolean cannotSendNext;
	public static CRC32 checksum;
	private static byte[] reply;
	private static BufferedInputStream fileInStream;
	private static int fileSize; 

	public static void main(String[] args) throws Exception {
		if (args.length != 4) {
			System.err.println("Usage: FileSender <host> <port> <current filename> <saved filename>");
			System.exit(-1);
		}

		DatagramSocket sk = new DatagramSocket();

		//Read command line arguments
		readArguments(args);

		//Creates a socket address from an IP address <args[0]> and a port number <args[1]>
		InetSocketAddress addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));

		//Setting up readFile (might throw FileNotFound)
		setUpFileRead(fileName);

		//Packaging the data into packets
		byte[] readData = new byte[SIZE_PACKET - INDEX_CONTENT];
		int bytes = 0;
		File inFile = new File(fileName);
		fileSize = (int) inFile.length();
		int tempSize = fileSize;

		while(bytes != -1 && bytes < fileSize) { //Initially, bytes = 0 for seqNo 0. Subsequently, bytes = number of bytes read from file

			byte[] content = new byte[SIZE_PACKET];
 
			if(seqNumber == 0) { //Create file name packet
				content = createNameBA();
			}
			else { //Create file data packet
				if(tempSize < SIZE_PACKET - INDEX_CONTENT) {
					System.out.println("LAST BYTE!");
					byte[] smallData = new byte[tempSize];
					bytes = fileInStream.read(smallData);
					if(bytes == -1) {
						break;
					}
					content = createDataBA(smallData);
				} else {
					bytes = fileInStream.read(readData);
					content = createDataBA(readData);
					tempSize -= (SIZE_PACKET - INDEX_CONTENT);
					System.out.println("Remaining file size: " + tempSize);
				}
			}

			System.out.println("Sending packet number " + seqNumber);

			//Create and send packet, set cannotSendNext flag to TRUE
			DatagramPacket spkt = new DatagramPacket(content, SIZE_PACKET, addr);
			sk.send(spkt);
			cannotSendNext = true;

			while(cannotSendNext) {
				
				//Create byte[] and DatagramPacket to receive ACK
				byte[] reply = new byte[16];
				DatagramPacket rpkt = new DatagramPacket(reply, reply.length);  
				
				//Setting timeout to 100ms
				sk.setSoTimeout(100);	

				try {
					sk.receive(rpkt);
					if(!checkReplyValid(reply)) { //Reply corrupted, resend packet!
						System.out.println("Reply for packet " + seqNumber + " corrupted, resending packet " + seqNumber);
						sk.send(spkt);
					}
					else { //Reply valid, check ACK or NAK
						System.out.println("Checksum for reply packet verified");
						ByteBuffer replyBB = ByteBuffer.wrap(reply);
						if(isAck(replyBB)) { //Packet ACK-ed, set cannotSendNext to false!
							if(isCorrectSeq(replyBB)) {
								System.out.println("Packet with seq number: " + seqNumber + " received successfully!");
								cannotSendNext = false;
							}
							else {
								System.out.println("ACK with seq number : " + replyBB.getInt(INDEX_SEQ) + " received, resending packet: " + seqNumber);
								sk.send(spkt);
							}
						}
						else { //Packet NAK-ed, resend packet
							System.out.println("Packet with seq number: " + seqNumber + " corrupted, resend packet!");
							sk.send(spkt);
						}
					}
				} catch (SocketTimeoutException ste) {
					System.out.println("Waiting for ACK|NAK timeout, resending packet " + seqNumber);
					sk.send(spkt);
				}
			}
			seqNumber++; //Increment seq number by one!
		}

		System.out.println("File transfer completed, shutting down FileSender...");
		fileInStream.close();
		System.out.println("END");
	}

	private static boolean isAck(ByteBuffer r) {
		int ackOrNak = r.getInt(12);
		if(ackOrNak == ACK) {
			return true;
		}
		return false;
	}

	private static boolean isCorrectSeq(ByteBuffer r) {
		int packetSeq = r.getInt(INDEX_SEQ);
		if(packetSeq == seqNumber) {
			return true;
		}
		return false;
	}

	private static void readArguments(String[] arguments) throws Exception {
		host = InetAddress.getByName(arguments[0]);
		port = Integer.parseInt(arguments[1]);
		fileName = arguments[2];
		outputFileName = arguments[3];
	}

	private static void setUpFileRead(String fileName) throws FileNotFoundException {
		fileInStream = new BufferedInputStream(new FileInputStream(fileName));
	}

	private static boolean checkReplyValid(byte[] reply) {
		checksum = new CRC32();
		checksum.reset();
		checksum.update(reply, INDEX_SEQ, reply.length - INDEX_SEQ);

		ByteBuffer b = ByteBuffer.wrap(reply);
		b.rewind();
		long chksum = b.getLong(INDEX_CHECKSUM);

		if(checksum.getValue() != chksum) {
			return false;
		} else {
			return true;
		}
	}

	/**
	*	METHOD CREATES A NAME PACKET 
	**/
	private static byte[] createNameBA() {
		//Creating byte[]
		byte[] data = new byte[SIZE_PACKET];
		ByteBuffer b = ByteBuffer.wrap(data);
		b.clear();

		//Adding of file name bytes to the byte[]
		byte[] nameBytes = outputFileName.getBytes();
		for(int i = 0; i < nameBytes.length; i++) {
			data[i + INDEX_CONTENT] = nameBytes[i];	
		}

		//Adding headers to the byte[]
		b.rewind();
		b.putLong(0); //Save 8 bytes for checksum
		b.putInt(seqNumber); //should be 0
		b.putInt(PACKET_NAME); //should be 1 since packet type is name
		b.putInt(nameBytes.length); //number of bytes that name takes

		//Calculating checksum and adding to byte[]
		checksum = new CRC32();
		checksum.reset();
		checksum.update(data, INDEX_SEQ, data.length - INDEX_SEQ);
		long chksum = checksum.getValue();
		b.rewind(); //move pointer back to 0
		b.putLong(chksum); //contains checksum of INDEX_SEQ to end of packet

		//Return byte[]
		return data;
	}

	/**
	*	METHOD CREATES A DATA PACKET
	**/
	private static byte[] createDataBA(byte[] dataRead) {
		//Creating byte and adding content to byte[]
		byte[] data = new byte[SIZE_PACKET];
		ByteBuffer b = ByteBuffer.wrap(data);
		b.clear();
		
		//Adding content of byte[] to data byte[]
		for(int a = 0; a < dataRead.length; a++) {
			data[a + INDEX_CONTENT] = dataRead[a]; 
		}

		//Adding headers to the byte[]
		b.rewind();
		b.putLong(0);
		b.putInt(seqNumber);
		b.putInt(PACKET_DATA);
		b.putInt(dataRead.length);

		//Calculating checksum and adding to byte[]
		checksum = new CRC32();
		checksum.reset();
		checksum.update(data, INDEX_SEQ, data.length-INDEX_SEQ);
		long chksum = checksum.getValue();
		b.rewind();
		b.putLong(chksum);

		//Return data byte[]
		return data;
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	
	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for ( int j = 0; j < bytes.length; j++ ) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

}
