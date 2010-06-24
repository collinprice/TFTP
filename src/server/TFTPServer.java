package server;

/**********************************************************************
 * 
 * Collin Price
 * collin.price @ gmail.com
 * cprxv09 @ student.lnu.se
 * 880526
 * 
 * DV201 Assignment 4
 * 
 * TFTP Server
 * Apr 2010
 * 
 **********************************************************************/

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * TFTPServer
 * 
 * This class is an implementation of the TFTP PROTOCOL (REVISION 2) 
 * RFC1350 standard.
 *
 */

public class TFTPServer {
	public static final int TFTPPORT = 4970;
	public static final int BUFSIZE = 516;
	/* UNIX */
	public static final String READDIR = "./library/read/";
	public static final String WRITEDIR = "./library/write/";
	/* WINDOWS */
//	public static final String READDIR = ".\\library\\read\\";
//	public static final String WRITEDIR = ".\\library\\write\\";
	public static final short OP_RRQ = 1;
	public static final short OP_WRQ = 2;
	public static final short OP_DAT = 3;
	public static final short OP_ACK = 4;
	public static final short OP_ERR = 5;
	public static final short ERR_LOST = 0;
	public static final short ERR_FNF = 1;
	public static final short ERR_ACCESS = 2;
	public static final short ERR_EXISTS = 6;
	public static String 	  mode;

	public static final String[] errorCodes = {"Not defined", "File not found.", "Access violation.", 
												"Disk full or allocation exceeded.", "Illegal TFTP operation.", 
												"Unknown transfer ID.", "File already exists.", 
												"No such user."};
	
	public static void main(String[] args) {
		if (args.length > 0) {
			System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
			System.exit(1);
		}
		try {
			TFTPServer server= new TFTPServer();
			server.start();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}
	
	private void start() throws SocketException {
		byte[] buf= new byte[BUFSIZE];
		
		/* Create socket */
		DatagramSocket socket= new DatagramSocket(null);
		
		/* Create local bind point */
		SocketAddress localBindPoint= new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		while(true) {        /* Loop to handle various requests */
			final InetSocketAddress clientAddress = 
				receiveFrom(socket, buf);
			if (clientAddress == null) /* If clientAddress is null, an error occurred in receiveFrom()*/
				continue;

			final StringBuffer requestedFile = new StringBuffer();
			final int reqtype = ParseRQ(buf, requestedFile);

			new Thread() {
				public void run() {
					try {
						DatagramSocket sendSocket = new DatagramSocket(0);
						sendSocket.connect(clientAddress);
												
						System.out.printf("%s request for %s from %s using port %d\n",
								(reqtype == OP_RRQ)?"Read":"Write", requestedFile.toString(),
								clientAddress.getHostName(), clientAddress.getPort());  

						if (reqtype == OP_RRQ) {      /* read request */
							requestedFile.insert(0, READDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
						}
						else {                       /* write request */
							requestedFile.insert(0, WRITEDIR);
							HandleRQ(sendSocket,requestedFile.toString(),OP_WRQ);  
						}
						sendSocket.close();
					} catch (SocketException e) {
						e.printStackTrace();
					}
				}
			}.start();
		}
	} // start
	
	
	/**
	 * receiveFrom
	 * 
	 * Reads the first block of data, i.e., the request for action (read or write).
	 * @param socket socket to read from
	 * @param buf where to store the read data
	 * @return the Internet socket address of the client
	 */
	private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
		DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
		
		try {
			socket.receive(receivePacket);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		InetSocketAddress client = new InetSocketAddress(receivePacket.getAddress(),receivePacket.getPort());
		
		return client;
	} // receiveFrom

	/**
	 * ParseRQ
	 * 
	 * parse the request for the type of request.
	 * 
	 * @param buf received request
	 * @param requestedFile
	 * @return request_type
	 */
	private short ParseRQ(byte[] buf, StringBuffer requestedFile) {
		ByteBuffer wrap = ByteBuffer.wrap(buf);
		short opcode = wrap.getShort();
		int delimiter = -1;
		for (int i = 2; i < buf.length; i++) {
			if (buf[i] == 0) {
				delimiter = i;
				break;
			}
		}
		
		if (delimiter == -1) {
			System.err.println("Corrupt request packet. Shutting down I guess.");
			System.exit(1);
		}
		
		String fileName = new String(buf, 2, delimiter-2);
//		System.out.println("Requested file = " + fileName);
		requestedFile.append(fileName);
		
		for (int i = delimiter+1; i < buf.length; i++) {
			if (buf[i] == 0) {
				String temp = new String(buf,delimiter+1,i-(delimiter+1));
//				System.out.println("Transfer mode = " + temp);
				mode = temp;
				if (temp.equalsIgnoreCase("octet")) {
					return opcode;
				} else {
					System.err.println("No mode specified.");
					System.exit(1);
				}
			}
		}
		System.err.println("Did not find delimiter.");
		System.exit(1);
		return 0;
	} // ParseRQ

	/**
	 * HandleRQ
	 * 
	 * @param sendSocket
	 * @param string directory and filename is file
	 * @param opRrq read or write
	 */
	private void HandleRQ(DatagramSocket sendSocket, String string, int opRrq) {
		System.out.println(string);
		File file = new File(string);
		byte[] buf = new byte[BUFSIZE-4];
		
		if (opRrq == OP_RRQ) {
			FileInputStream in = null;
			try {
				in = new FileInputStream(file);
			} catch (FileNotFoundException e) {
				System.err.println("File not found. Sending error packet.");
				sendError(sendSocket, ERR_FNF, "");
				return;
			}
			
			short blockNum = 1;
			
			while (true) {
				
				int length;
				try {
					length = in.read(buf);
				} catch (IOException e) {
					System.err.println("Error reading file.");
					return;
				}
				
				if (length == -1) {
					length = 0;
				}
				DatagramPacket sender = dataPacket(blockNum, buf, length);
				System.out.println("Sending.........");
				if (WriteAndReadAck(sendSocket, sender, blockNum++)) {
					System.out.println("Success. Send another. blockNum = " + blockNum);
				} else {
					System.err.println("Error. Lost connection.");
					sendError(sendSocket,ERR_LOST, "Lost connection.");
					return;
				}
				
				if (length < 512) {
					try {
						in.close();
					} catch (IOException e) {
						System.err.println("Trouble closing file.");
					}
					break;
				}
			}
		} else if (opRrq == OP_WRQ) {
			if (file.exists()) {
				System.out.println("File already exists.");
				sendError(sendSocket, ERR_EXISTS, "File already exists.");
				return;
			} else {
				FileOutputStream output = null;
				try {
					output = new FileOutputStream(file);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
					sendError(sendSocket, ERR_ACCESS, "Could not create file.");
					return;
				}
				
				short blockNum = 0;
				
				while (true) {
					DatagramPacket dataPacket = ReadAndWriteData(sendSocket, ackPacket(blockNum++), blockNum);
					
					if (dataPacket == null) {
						System.err.println("Error. Lost connection.");
						sendError(sendSocket,ERR_LOST, "Lost connection.");
						try {
							output.close();
						} catch (IOException e) {
							System.err.println("Could not close file. Meh.");
						}
						System.out.println("Deleting incomplete file.");
						file.delete();
						break;
					} else {
						byte[] data = dataPacket.getData();
						try {
							output.write(data, 4, dataPacket.getLength()-4);
							System.out.println(dataPacket.getLength());
						} catch (IOException e) {
							System.err.println("IO Error writing data.");
							sendError(sendSocket,ERR_ACCESS, "Trouble writing data.");
						}
						if (dataPacket.getLength()-4 < 512) {
							try {
								sendSocket.send(ackPacket(blockNum));
							} catch (IOException e1) {
								try {
									sendSocket.send(ackPacket(blockNum));
								} catch (IOException e) {
								}
							}
							System.out.println("All done writing file.");
							try {
								output.close();
							} catch (IOException e) {
								System.err.println("Could not close file. Meh.");
							}
							break;
						}
					}
				}
			}
		} else {
			System.err.println("Um... I do not know what to do now so I will stop.");
		}
		
	} // HandleRQ
	
	/**
	 * ReadAndWriteData
	 * 
	 * Handles sending an OP_ACK packet and receiving a data packet.
	 * 
	 * @param sendSocket
	 * @param sendAck OP_ACK packet to be sent
	 * @param block block number it is expecting
	 * @return data packet or null is connection timeout
	 */
	private DatagramPacket ReadAndWriteData(DatagramSocket sendSocket, DatagramPacket sendAck, short block) {
		int retryCount = 0;
		byte[] rec = new byte[BUFSIZE];
		DatagramPacket receiver = new DatagramPacket(rec, rec.length);

        while(true) {
            if (retryCount >= 6) {
                System.err.println("Timed out. Closing connection.");
                return null;
            }
            try {
            	System.out.println("sending ack for block: " + block);
            	sendSocket.send(sendAck);
                sendSocket.setSoTimeout(((int) Math.pow(2, retryCount++))*1000);
                sendSocket.receive(receiver);
                
                short blockNum = getData(receiver);
                System.out.println(blockNum + " " + block);
                if (blockNum == block) {
                	return receiver;
                } else if (blockNum == -1) {
                	return null;
                } else {
                	System.out.println("Duplicate.");
                	retryCount = 0;
                	throw new SocketTimeoutException();
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout.");
                try {
					sendSocket.send(sendAck);
				} catch (IOException e1) {
					System.err.println("Error sending...");
				}
            } catch (IOException e) {
				System.err.println("IO Error.");
			} finally {
                try {
					sendSocket.setSoTimeout(0);
				} catch (SocketException e) {
					System.err.println("Error resetting Timeout.");
				}
            }
        }
	} // ReadAndWriteData
	
	/**
	 * WriteAndReadAck
	 * 
	 * Handles sending a data packet and receiving an ack packet.
	 * 
	 * @param sendSocket
	 * @param sender
	 * @param blockNum
	 * @return true if successful OP_ACK. false if connection timeout.
	 */
	private boolean WriteAndReadAck(DatagramSocket sendSocket, DatagramPacket sender, short blockNum) {
		int retryCount = 0;
		byte[] rec = new byte[BUFSIZE];
		DatagramPacket receiver = new DatagramPacket(rec, rec.length);
		
		while(true) {
			if (retryCount >= 6) {
	            System.err.println("Timed out. Closing connection.");
	            return false;
	        }
	        try {
	            sendSocket.send(sender);
	            System.out.println("Sent.");
	            sendSocket.setSoTimeout(((int) Math.pow(2, retryCount++))*1000);
	            sendSocket.receive(receiver);
	            
	            /* _______________ Dissect Datagram and Test _______________ */
	            short ack = getAck(receiver);
//	            System.out.println("Ack received: " + ack);
	            if (ack == blockNum) {
//	            	System.out.println("Received correct OP_ACK");
	            	return true;
	            } else if (ack == -1) {
	            	return false;
	            } else {
//	            	System.out.println("Ignore. Wrong ack.");
	            	retryCount = 0;
	            	throw new SocketTimeoutException();
	            }
	            /* ^^^^^^^^^^^^^^^ Dissect Datagram and Test ^^^^^^^^^^^^^^^ */
	            
	        } catch (SocketTimeoutException e) {
	            System.out.println("Timeout. Resending.");
	        } catch (IOException e) {
				System.err.println("IO Error. Resending.");
			} finally {
	            try {
					sendSocket.setSoTimeout(0);
				} catch (SocketException e) {
					System.err.println("Error resetting Timeout.");
				}
	        }
		}
	} // WriteAndReadAck
	
	/**
	 * ackPacket
	 * 
	 * Constructs an ack packet for the given block number.
	 * 
	 * @param block
	 * @return ackPacket
	 */
	private DatagramPacket ackPacket(short block) {
		
		ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
        buffer.putShort(OP_ACK);
        buffer.putShort(block);
		
        return new DatagramPacket(buffer.array(), 4);
	} // ackPacket
	
	/**
	 * dataPacket
	 * 
	 * Constructs an OP_DAT packet
	 * 
	 * @param block current block number
	 * @param data data to be sent
	 * @param length lengh of data
	 * @return DatagramPacket to be sent
	 */
	private DatagramPacket dataPacket(short block, byte[] data, int length) {
		
		ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
        buffer.putShort(OP_DAT);
        buffer.putShort(block);
        buffer.put(data, 0, length);
		
        return new DatagramPacket(buffer.array(), 4+length);
	} // dataPacket
	
	/**
	 * getAck
	 * 
	 * Returns the OP_ACK packer number.
	 * 
	 * @param ack Received Datagram Packet
	 * @return OP_ACK number or -1 if not an OP_ACK
	 */
	private short getAck(DatagramPacket ack) {
		ByteBuffer buffer = ByteBuffer.wrap(ack.getData());
		short opcode = buffer.getShort();
		if (opcode == OP_ERR) {
			System.err.println("Client is dead. Closing connection.");
			parseError(buffer);
			return -1;
		}
		
		return buffer.getShort();
	} // getAck
	
	/**
	 * getData
	 * 
	 * Returns the block number of the data packet.
	 * 
	 * @param data Received datagram packet.
	 * @return block number or -1 if connection dropped.
	 */
	private short getData(DatagramPacket data) {
		ByteBuffer buffer = ByteBuffer.wrap(data.getData());
		short opcode = buffer.getShort();
		if (opcode == OP_ERR) {
			System.err.println("Client is dead. Closing connection.");
			parseError(buffer);
			return -1;
		}
		
		return buffer.getShort();
	} // getData
	
	/**
	 * sendError
	 * 
	 * Sends a single error packet to sendSocket
	 * 
	 * @param sendSocket
	 * @param errorCode
	 * @param errMsg
	 */
	private void sendError(DatagramSocket sendSocket, short errorCode, String errMsg) {
		
		ByteBuffer wrap = ByteBuffer.allocate(BUFSIZE);
		wrap.putShort(OP_ERR);
		wrap.putShort(errorCode);
		wrap.put(errMsg.getBytes());
		wrap.put((byte) 0);
		
		DatagramPacket receivePacket = new DatagramPacket(wrap.array(),wrap.array().length);
		try {
			sendSocket.send(receivePacket);
		} catch (IOException e) {
			System.err.println("Problem sending error packet.");
			e.printStackTrace();
		}
		
	} // sendError
	
	/**
	 * parseError
	 * 
	 * Parses an error message and displays its contents.
	 * 
	 * @param buffer
	 */
	private void parseError(ByteBuffer buffer) {
		
		short errCode = buffer.getShort();
		
		byte[] buf = buffer.array();
		for (int i = 4; i < buf.length; i++) {
			if (buf[i] == 0) {
				String msg = new String(buf, 4, i - 4);
				if (errCode > 7) errCode = 0;
				System.err.println(errorCodes[errCode] + ": " + msg);
				break;
			}
		}
		
	} // parseError
	
} // TFTPServer



