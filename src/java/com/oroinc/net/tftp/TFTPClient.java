/***
 * $Id: TFTPClient.java,v 1.1 2002/04/03 01:04:39 brekke Exp $
 *
 * NetComponents Internet Protocol Library
 * Copyright (C) 1997-2002  Daniel F. Savarese
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library in the LICENSE file; if not, write
 * to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA  02111-1307  USA
 ***/

package com.oroinc.net.tftp;

import java.io.*;
import java.net.*;

import com.oroinc.io.*;

/***
 * The TFTPClient class encapsulates all the aspects of the TFTP protocol
 * necessary to receive and send files through TFTP.  It is derived from
 * the <a href="com.oroinc.net.tftp.TFTP.html"> TFTP class </a> because
 * it is more convenient than using aggregation, and as a result exposes 
 * the same set of methods to allow you to deal with the TFTP protocol
 * directly.  However, almost every user should only be concerend with the
 * the <a href="com.oroinc.net.DatagramSocketClient.html#open"> open() </a>,
 * <a href="com.oroinc.net.DatagramSocketClient.html#close"> close() </a>,
 * <a href="#sendFile"> sendFile() </a>, and
 * <a href="#receiveFile"> receiveFile() </a> methods.  Additionally, the
 * <a href="#setMaxTimeouts"> setMaxTimeouts() </a> and
 * <a href="com.oroinc.net.DatagramSocketClient.html#setDefaultTimeout">
 * setDefaultTimeout() </a> methods may be of importance for performance
 * tuning.
 * <p>
 * Details regarding the TFTP protocol and the format of TFTP packets can
 * be found in RFC 783.  But the point of these classes is to keep you
 * from having to worry about the internals.
 * <p>
 * <p>
 * @author Daniel F. Savarese
 * @see TFTP
 * @see TFTPPacket
 * @see TFTPPacketException
 ***/

public class TFTPClient extends TFTP {
  /***
   * The default number of times a receive attempt is allowed to timeout
   * before ending attempts to retry the receive and failing.  The default
   * is 5 timeouts.
   ***/
  public static final int DEFAULT_MAX_TIMEOUTS = 5;

  /*** The maximum number of timeouts allowed before failing. ***/
  private int __maxTimeouts;

  /***
   * Creates a TFTPClient instance with a default timeout of DEFAULT_TIMEOUT,
   * maximum timeouts value of DEFAULT_MAX_TIMEOUTS, a null socket,
   * and buffered operations disabled.
   ***/
  public TFTPClient() {
    __maxTimeouts   = DEFAULT_MAX_TIMEOUTS;
  }

  /***
   * Sets the maximum number of times a receive attempt is allowed to
   * timeout during a receiveFile() or sendFile() operation before ending
   * attempts to retry the receive and failing.
   * The default is DEFAULT_MAX_TIMEOUTS.
   * <p>
   * @param numTimeouts  The maximum number of timeouts to allow.  Values
   *        less than 1 should not be used, but if they are, they are
   *        treated as 1.
   ***/
  public void setMaxTimeouts(int numTimeouts) {
    if(__maxTimeouts < 1)
      __maxTimeouts = 1;
    else
      __maxTimeouts = numTimeouts;
  }

  /***
   * Returns the maximum number of times a receive attempt is allowed to
   * timeout before ending attempts to retry the receive and failing.
   * <p>
   * @return The maximum number of timeouts allowed.
   ***/
  public int getMaxTimeouts() { return __maxTimeouts; }


  /***
   * Requests a named file from a remote host, writes the
   * file to an OutputStream, closes the connection, and returns the number
   * of bytes read.  A local UDP socket must first be created by
   * <a href="com.oroinc.net.DatagramSocketClient.html#open">open()</a> before
   * invoking this method.  This method will not close the OutputStream
   * containing the file; you must close it after the method invocation.
   * <p>
   * @param filename  The name of the file to receive.
   * @param mode   The TFTP mode of the transfer (one of the MODE constants).
   * @param output The OutputStream to which the file should be written.
   * @param host   The remote host serving the file.
   * @param port   The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   ***/
  public int receiveFile(String filename, int mode, OutputStream output,
			 InetAddress host, int port) throws IOException
  {
    int bytesRead, timeouts, lastBlock, block, hostPort, dataLength;
    TFTPPacket sent, received = null;
    TFTPErrorPacket error;
    TFTPDataPacket  data;
    TFTPAckPacket   ack = new TFTPAckPacket(host, port, 0);

    beginBufferedOps();

    dataLength = lastBlock = hostPort = bytesRead = 0;
    block = 1;

    if(mode == TFTP.ASCII_MODE)
      output = new FromNetASCIIOutputStream(output);

    sent =
      new TFTPReadRequestPacket(host, port, filename, mode);

  _sendPacket:
    do {
      bufferedSend(sent);

    _receivePacket:
      while(true) {
	timeouts = 0;
	while(timeouts < __maxTimeouts) {
	  try {
	    //System.err.println("Receiving.");
	    received = bufferedReceive();
	    break;
	  } catch(SocketException e) {
	    if(++timeouts >= __maxTimeouts) {
	      endBufferedOps();
	      throw new IOException("Connection timed out.");
	    }
	    continue;
	  } catch(InterruptedIOException e) {
	    if(++timeouts >= __maxTimeouts) {
	      endBufferedOps();
	      throw new IOException("Connection timed out.");
	    }
	    continue;
	  } catch(TFTPPacketException e) {
	    endBufferedOps();
	    throw new IOException("Bad packet: " + e.getMessage());
	  }
	}
    
	//System.err.println("Got an answer.");

	// The first time we receive we get the port number.
	if(lastBlock == 0) {
	  hostPort = received.getPort();
	  ack.setPort(hostPort);
	}

	// Comply with RFC 783 indication that an error acknowledgement
	// should be sent to originator if unexpected TID or host.
	if(host.equals(received.getAddress()) &&
	   received.getPort() == hostPort) {

	  //System.err.println("Got this far.");

	  switch(received.getType()) {
	  case TFTPPacket.ERROR:
	    error = (TFTPErrorPacket)received;
	    endBufferedOps();
	    throw new IOException("Error code " + error.getError() +
				  " received: " + error.getMessage());
	  case TFTPPacket.DATA:
	    data = (TFTPDataPacket)received;
	    dataLength = data.getDataLength();

	    lastBlock = data.getBlockNumber();

	    //System.err.println("Data: Got Block: " + lastBlock +" Expected: " + block);

	    if(lastBlock == block) {
	      try {
		output.write(data.getData(), data.getDataOffset(),
			     dataLength);
	      } catch(IOException e){
		error = new TFTPErrorPacket(host, hostPort,
					    TFTPErrorPacket.OUT_OF_SPACE,
					    "File write failed.");
		bufferedSend(error);
		endBufferedOps();
		throw e;
	      }
	      ++block;
	      break _receivePacket;
	    } else {
	      discardPackets();

	      if(lastBlock == (block - 1))
		continue _sendPacket;  // Resend last acknowledgement.

	      continue _receivePacket; // Start fetching packets again.
	    }
	    //break;
	  default:
	    endBufferedOps();
	    throw new IOException("Received unexpected packet type.");
	  }
	} else {
	  error = new TFTPErrorPacket(received.getAddress(),
				      received.getPort(),
				      TFTPErrorPacket.UNKNOWN_TID,
				      "Unexpected host or port.");
	  bufferedSend(error);
	  continue _sendPacket;
	}

	// We should never get here, but this is a safety to avoid
	// infinite loop.  If only Java had the goto statement.
	//break;
      }

      //System.err.println("Setting acknowledgement: " + lastBlock);
      ack.setBlockNumber(lastBlock);
      sent = ack;
      bytesRead+=dataLength;
      //System.err.println("bytesRead: " + bytesRead);
    }// First data packet less than 512 bytes signals end of stream.
    while(dataLength == TFTPPacket.SEGMENT_SIZE);

    bufferedSend(sent);
    endBufferedOps();

    return bytesRead;
  }


  /***
   * Requests a named file from a remote host, writes the
   * file to an OutputStream, closes the connection, and returns the number
   * of bytes read.  A local UDP socket must first be created by
   * <a href="com.oroinc.net.DatagramSocketClient.html#open">open()</a> before
   * invoking this method.  This method will not close the OutputStream
   * containing the file; you must close it after the method invocation.
   * <p>
   * @param filename The name of the file to receive.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The OutputStream to which the file should be written.
   * @param hostname The name of the remote host serving the file.
   * @param port     The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   * @exception UnknownHostException  If the hostname cannot be resolved.
   ***/
  public int receiveFile(String filename, int mode, OutputStream output,
			 String hostname, int port)
       throws UnknownHostException, IOException
  {
    return receiveFile(filename, mode, output, InetAddress.getByName(hostname),
		       port);
  }


  /***
   * Same as calling
   * <code> receiveFile(filename, mode, output, host, TFTP.DEFAULT_PORT)
   * </code> <p>
   * @param filename The name of the file to receive.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The OutputStream to which the file should be written.
   * @param host     The remote host serving the file.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   ***/
  public int receiveFile(String filename, int mode, OutputStream output,
		         InetAddress host)
       throws IOException
  {
    return receiveFile(filename, mode, output, host, DEFAULT_PORT);
  }

  /***
   * Same as calling
   * <code> receiveFile(filename, mode, output, hostname, TFTP.DEFAULT_PORT)
   * </code> <p>
   * @param filename The name of the file to receive.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The OutputStream to which the file should be written.
   * @param hostname The name of the remote host serving the file.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   * @exception UnknownHostException  If the hostname cannot be resolved.
   ***/
  public int receiveFile(String filename, int mode, OutputStream output,
			  String hostname)
       throws UnknownHostException, IOException
  {
    return receiveFile(filename, mode, output, InetAddress.getByName(hostname),
		       DEFAULT_PORT);
  }


  /***
   * Requests to send a file to a remote host, reads the file from an
   * InputStream, sends the file to the remote host, and closes the
   * connection.  A local UDP socket must first be created by
   * <a href="com.oroinc.net.DatagramSocketClient.html#open">open()</a> before
   * invoking this method.  This method will not close the InputStream
   * containing the file; you must close it after the method invocation.
   * <p>
   * @param filename The name the remote server should use when creating
   *        the file on its file system.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The InputStream containing the file.
   * @param host     The remote host receiving the file.
   * @param port     The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   ***/
  public void sendFile(String filename, int mode, InputStream input,
	               InetAddress host, int port) throws IOException {
    int bytesRead, timeouts, lastBlock, block, hostPort, dataLength, offset;
    TFTPPacket sent, received = null;
    TFTPErrorPacket error;
    TFTPDataPacket  data =
      new TFTPDataPacket(host, port, 0, _sendBuffer, 4, 0);;
    TFTPAckPacket ack;

    beginBufferedOps();

    dataLength = lastBlock = hostPort = bytesRead = 0;
    block = 0;

    if(mode == TFTP.ASCII_MODE)
      input = new ToNetASCIIInputStream(input);

    sent =
      new TFTPWriteRequestPacket(host, port, filename, mode);

  _sendPacket:
    do {
      bufferedSend(sent);

    _receivePacket:
      while(true) {
	timeouts = 0;
	while(timeouts < __maxTimeouts) {
	  try {
	    //System.err.println("Receiving.");
	    received = bufferedReceive();
	    break;
	  } catch(SocketException e) {
	    if(++timeouts >= __maxTimeouts) {
	      endBufferedOps();
	      throw new IOException("Connection timed out.");
	    }
	    continue;
	  } catch(InterruptedIOException e) {
	    if(++timeouts >= __maxTimeouts) {
	      endBufferedOps();
	      throw new IOException("Connection timed out.");
	    }
	    continue;
	  } catch(TFTPPacketException e) {
	    endBufferedOps();
	    throw new IOException("Bad packet: " + e.getMessage());
	  }
	}
    
	//System.err.println("Got an answer.");

	// The first time we receive we get the port number.
	if(lastBlock == 0) {
	  hostPort = received.getPort();
	  data.setPort(hostPort);
	}

	// Comply with RFC 783 indication that an error acknowledgement
	// should be sent to originator if unexpected TID or host.
	if(host.equals(received.getAddress()) &&
	   received.getPort() == hostPort) {

	  //System.err.println("Got this far.");

	  switch(received.getType()) {
	  case TFTPPacket.ERROR:
	    error = (TFTPErrorPacket)received;
	    endBufferedOps();
	    throw new IOException("Error code " + error.getError() +
				  " received: " + error.getMessage());
	  case TFTPPacket.ACKNOWLEDGEMENT:
	    ack = (TFTPAckPacket)received;

	    lastBlock = ack.getBlockNumber();

	    //System.err.println("Ack: Got Block: " + lastBlock +" Expected: " + block);

	    if(lastBlock == block) {
	      ++block;
	      break _receivePacket;
	    } else {
	      discardPackets();

	      if(lastBlock == (block - 1))
		continue _sendPacket;  // Resend last acknowledgement.

	      continue _receivePacket; // Start fetching packets again.
	    }
	    //break;
	  default:
	    endBufferedOps();
	    throw new IOException("Received unexpected packet type.");
	  }
	} else {
	  error = new TFTPErrorPacket(received.getAddress(),
				      received.getPort(),
				      TFTPErrorPacket.UNKNOWN_TID,
				      "Unexpected host or port.");
	  bufferedSend(error);
	  continue _sendPacket;
	}

	// We should never get here, but this is a safety to avoid
	// infinite loop.  If only Java had the goto statement.
	//break;
      }

      dataLength = TFTPPacket.SEGMENT_SIZE;
      offset     = 4;
      while(dataLength > 0 && 
	    (bytesRead = input.read(_sendBuffer, offset, dataLength)) > 0) {
	offset+=bytesRead;
	dataLength-=bytesRead;
      }

      data.setBlockNumber(block);
      data.setData(_sendBuffer, 4, offset - 4);
      sent = data;
    } while(dataLength == 0);

    bufferedSend(sent);
    endBufferedOps();
  }


  /***
   * Requests to send a file to a remote host, reads the file from an
   * InputStream, sends the file to the remote host, and closes the
   * connection.  A local UDP socket must first be created by
   * <a href="com.oroinc.net.DatagramSocketClient.html#open">open()</a> before
   * invoking this method.  This method will not close the InputStream
   * containing the file; you must close it after the method invocation.
   * <p>
   * @param filename The name the remote server should use when creating
   *        the file on its file system.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The InputStream containing the file.
   * @param hostname The name of the remote host receiving the file.
   * @param port     The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   * @exception UnknownHostException  If the hostname cannot be resolved.
   ***/
  public void sendFile(String filename, int mode, InputStream input,
		       String hostname, int port)
       throws UnknownHostException, IOException
  {
    sendFile(filename, mode, input, InetAddress.getByName(hostname), port);
  }


  /***
   * Same as calling 
   * <code> sendFile(filename, mode, input, host, TFTP.DEFAULT_PORT); </code>
   * <p>
   * @param filename The name the remote server should use when creating
   *        the file on its file system.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The InputStream containing the file.
   * @param hostname The name of the remote host receiving the file.
   * @param port     The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   * @exception UnknownHostException  If the hostname cannot be resolved.
   ***/
  public void sendFile(String filename, int mode, InputStream input,
		       InetAddress host)
       throws IOException
  {
    sendFile(filename, mode, input, host, DEFAULT_PORT);
  }

  /***
   * Same as calling 
   * <code> sendFile(filename, mode, input, hostname, TFTP.DEFAULT_PORT);
   * </code> <p>
   * @param filename The name the remote server should use when creating
   *        the file on its file system.
   * @param mode     The TFTP mode of the transfer (one of the MODE constants).
   * @param output   The InputStream containing the file.
   * @param hostname The name of the remote host receiving the file.
   * @param port     The port number of the remote TFTP server.
   * @exception IOException If an I/O error occurs.  The nature of the
   *            error will be reported in the message.
   * @exception UnknownHostException  If the hostname cannot be resolved.
   ***/
  public void sendFile(String filename, int mode, InputStream input,
		       String hostname)
       throws UnknownHostException, IOException
  {
    sendFile(filename, mode, input, InetAddress.getByName(hostname),
	     DEFAULT_PORT);
  }
}
