package com.commander4j.labeller;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.commander4j.util.JUtility;
import com.opencsv.CSVReader;

public class Labeller extends Thread
{
	private LabellerProperties prop;
	private Socket socket = new Socket();
	private volatile boolean shutdown = false;
	private LabellerTCPIP_RX rx;
	private LabellerTCPIP_TX tx;
	private boolean connected = false;
	private LabellerUtility utils = new LabellerUtility();
	private LabellerCMDFile labellerCMDFile = new LabellerCMDFile();
	private String commandFile = "";
	private boolean requestPrint = false;

	private File fileWrite;
	private int waitRetries = 20;
	private int waitDelay = 15;
	public volatile ConcurrentLinkedQueue<LabellerFile> directory = new ConcurrentLinkedQueue<LabellerFile>();
	Logger logger = org.apache.logging.log4j.LogManager.getLogger((Labeller.class));

	public void requestPrint()
	{

		requestPrint = true;
	}

	public boolean requestRunning()
	{
		return requestPrint;
	}

	private boolean parseInputData(String fullFilename)
	{
		boolean result = false;
		logger.info("Labeller [" + prop.getId() + "] parseInputData [" + fullFilename + "]");

		try
		{
			CSVReader reader = new CSVReader(new FileReader(fullFilename));
			String[] headerLine;
			String[] dataLine;

			headerLine = reader.readNext();
			dataLine = reader.readNext();

			labellerCMDFile.fileData.clear();

			for (int x = 0; x < headerLine.length; x++)
			{
				labellerCMDFile.fileData.put("$data_" + headerLine[x], dataLine[x]);
			}

			reader.close();

			result = true;

		} catch (Exception ex)
		{
			logger.error("Labeller [" + prop.getId() + "] parseInputData error :" + ex.getMessage());
		}

		return result;
	}

	@Override
	public void run()
	{
		logger.info("Labeller run start");
		logger.info("Labeller " + prop.getId());

		connected = false;
		shutdown = false;

		while ((shutdown == false))
		{

			/* ADD CODE HERE TO POLL FOLDERS FOR DATA */
			File inputData = new File(prop.getInputPath() + prop.getInputFile());
			requestPrint = inputData.exists();

			/* PARSE DATA */

			if (requestPrint)
			{
				logger.debug("[" + prop.getId() + "] detected input file [" + prop.getInputPath() + prop.getInputFile() + "]");
				parseInputData(prop.getInputPath() + prop.getInputFile());

				if (connected == false)
				{
					connected = connect();

					if (connected)
					{

						utils.pause(250);

						if (tx.getStatus() != LabellerTCPIP_TX.status_RUNNING)
						{
							logger.info(tx.getStatusName());
							connected = false;
						}

						if (rx.getStatus() != LabellerTCPIP_RX.status_RUNNING)
						{
							logger.info(rx.getStatusName());
							connected = false;
						}
					}

				} else
				{

					if (requestPrint == true)
					{
						if (runScript())
						{
							/* DELETE FILE */
							inputData.delete();
							requestPrint = false;
						}
					}

					if (connected)
					{
						disconnect();
					}
				}
			} else
			{
				// System.out.println("Labeller ["+ prop.getId()+"] waiting for
				// request to print");
				utils.pause(250);
			}
		}

		logger.info("Labeller run end");

	}
	


	public boolean runScript()
	{

		boolean scriptError = false;

		String LBL = "";
		String CMD = "";
		String VAL = "";
		String DEL = "\"";
		String EOL = "";

		rx.ignoredResponses.clear();
		rx.clearQueue();
		rx.successResponses.clear();
		rx.failedResponses.clear();

		labellerCMDFile.loadFile(prop, commandFile);

		int exePosition = labellerCMDFile.getLinefromLabel("Initialise");

		while ((requestPrint == true) && (connected == true) && (scriptError == false))
		{

			LBL = labellerCMDFile.commandLines.get(exePosition).label;
			CMD = labellerCMDFile.commandLines.get(exePosition).command;
			VAL = labellerCMDFile.getValueAtLine(exePosition).replaceAll("~", "\"");


			logger.info("[" + prop.getId() + "]" + " runScript :" + JUtility.padString(String.valueOf(exePosition + 1), false, 5, "0") + " - " + JUtility.padString(LBL, true, 18, " ") + JUtility.padString(CMD, true, 30, " ")
					+ JUtility.padString(VAL, true, 50, " "));

			switch (CMD)
			{
			case "MESSAGE_INFO":
				tx.send("*MSG,info," + VAL + "<CR>");
				checkSuccess();
				break;
			case "MESSAGE_ERROR":
				tx.send("*MSG,error," + VAL + "<CR>");
				checkSuccess();
				break;
			case "MESSAGE_WARN":
				tx.send("*MSG,warning," + VAL + "<CR>");
				checkSuccess();
				break;
			case "VARIABLE":
				String variableDefinition = VAL;
				String[] varArray = StringUtils.split(variableDefinition, "=");
				labellerCMDFile.variables.put(varArray[0], labellerCMDFile.removeDelimitors(varArray[1]));
				break;
			case "USER_INPUT":
				String variableDefinition2 = VAL;
				String[] varArray2 = StringUtils.split(labellerCMDFile.removeDelimitors(variableDefinition2), ",");
				Scanner c = new Scanner(System.in);

				String prompt = varArray2[0];
				String defaultValue = varArray2[1];
				String variableName = varArray2[2];
				System.out.print(prompt + " : ");
				String input = c.nextLine();

				if (input.equals(""))
				{
					input = defaultValue;
				}
				labellerCMDFile.variables.put(variableName, input);

				break;
			case "EXIT":
				requestPrint = false;
				utils.pause(100);
				break;
			case "GOTO":
				exePosition = labellerCMDFile.getLinefromLabel(VAL) - 1;
				break;
			case "PAUSE":
				utils.pause(Integer.valueOf(VAL));
				break;
			case "ECHO":
				System.out.println(VAL);
				break;
			case "DELETE_FILE":
				tx.send("*DELETE," + VAL + "<CR>");
				checkSuccess();
				break;
			case "DIR_REMOTE":
				if (DIR_REMOTE(VAL) == false)
				{
					scriptError = true;
				}
				break;
			case "STRING_DELIMITER":
				DEL = VAL;
				labellerCMDFile.setDelimiter(DEL);
				break;
			case "SEND_EOL":
				EOL = utils.encodeControlChars(VAL);
				break;
			case "RESPONSE_EOL":
				rx.setResponseEOL(VAL);
				break;
			case "SEND":
				rx.clearQueue();
				tx.send(utils.encodeControlChars(VAL) + EOL);
				utils.pause(100);
				break;
			case "CHECK_SUCCESS":
				// scriptError = checkSuccess();
				break;
			case "IGNORE_RESPONSE":
				rx.ignoredResponses.add(VAL);
				break;
			case "FAIL_RESPONSE":
				rx.failedResponses.add(VAL);
				break;
			case "SUCCESS_RESPONSE":
				rx.successResponses.add(VAL);
				break;
			case "LOCAL_DELETE_FILE":
				String localfilename = System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId() + java.io.File.separator + VAL;
				logger.info("[" + prop.getId() + "]" + " Local Delete  [" + localfilename + "]");
				File fileDelete = new File(localfilename);
				FileUtils.deleteQuietly(fileDelete);
				fileDelete=null;
				break;				
			case "FILE_DEFINE":
				String writefilename = System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId() + java.io.File.separator + VAL;
				logger.info("[" + prop.getId() + "]" + " File Defined as  [" + writefilename + "]");
				fileWrite = new File(writefilename);
				if (FileUtils.deleteQuietly(fileWrite))
				{
					logger.info("[" + prop.getId() + "]" + " Existing file deleted  [" + writefilename + "]");
				}
				break;
			case "FILE_WRITE":
				File writePath = new File(System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId());
				try
				{
					FileUtils.forceMkdir(writePath);
				} catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				try
				{
					logger.info("[" + prop.getId() + "]" + " Write to file [" + fileWrite.getName() + "] <-- [" + VAL + "]");
					FileUtils.writeStringToFile(fileWrite, utils.encodeControlChars(VAL), "UTF-8", true);
				} catch (IOException e)
				{
					logger.error("[" + prop.getId() + "]" + " ERROR [" + e.getMessage() + "]");
					scriptError = true;
				}
				break;
			case "SEND_FILE_INTELHEX":
				if (SEND_FILE_INTELHEX(VAL) == false)
				{
					scriptError = true;
				}
				break;
			case "RECEIVE_FILE_INTELHEX":
				if (RECEIVE_FILE_INTELHEX(VAL, false) == false)
				{
					scriptError = true;
				}
				break;
			case "BACKUP_REMOTE":
				if (BACKUP_REMOTE(VAL) == false)
				{
					scriptError = true;
				}
				break;
			default:
			}

			exePosition++;
		}

		return true;
	}

	public boolean BACKUP_REMOTE(String mask)
	{
		boolean result = false;
		result = RECEIVE_FILE_INTELHEX(mask, true);
		return result;
	}

	public boolean SEND_FILE_INTELHEX(String VAL)
	{
		boolean result = false;

		File sendPath = new File(System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId());
		try
		{
			FileUtils.forceMkdir(sendPath);
		} catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		System.out.println("");
		logger.info("[" + prop.getId() + "]" + " SEND file [" + VAL + "] Started.");
		rx.clearQueue();
		String sendfilename = System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId() + java.io.File.separator + VAL;
		tx.send("*HEXFILE," + VAL + "<CR>");
		result = checkSuccess();
		if (result == true)
		{
			LabellerIntelHex ihc = new LabellerIntelHex();
			try
			{
				ihc.prepareToConvertToIntelHex(new File(sendfilename), LabellerIntelHex.CRLF_TO_CR);
				String block = ihc.getNextIntelHexBlock();
				while (block != null)
				{
					rx.clearQueue();
					tx.send(block);
					result = checkSuccess();
					block = ihc.getNextIntelHexBlock();
				}
				rx.clearQueue();
				tx.send(ihc.getEOFBlock());
				result = checkSuccess();
				rx.clearQueue();
				logger.info("[" + prop.getId() + "]" + " SEND file [" + VAL + "] Complete.");
				result = true;
			} catch (Exception e)
			{
				logger.error("[" + prop.getId() + "]" + " SEND file [" + VAL + "] Error." + e.getMessage());

			}
			System.out.println("");
		}
		return result;
	}

	public boolean RECEIVE_FILE_INTELHEX(String VAL, boolean isBackup)
	{
		boolean result = false;

		String downloadFileMask = VAL;
		result = DIR_REMOTE(downloadFileMask);

		File downloadPath;
		if (isBackup)
		{
			downloadPath = new File(System.getProperty("user.dir") + java.io.File.separator + "labeller_backups" + java.io.File.separator + prop.getId() + java.io.File.separator
					+ JUtility.getISOTimeStampStringFormat(JUtility.getSQLDateTime()).substring(0, 16).replaceAll(":", "-").replaceAll("T", "-"));
		} else
		{
			downloadPath = new File(System.getProperty("user.dir") + java.io.File.separator + "labeller_files" + java.io.File.separator + prop.getId());
		}

		try
		{
			FileUtils.forceMkdir(downloadPath);
		} catch (IOException e)
		{
			logger.error(e.getMessage());
		}

		System.out.println("");
		while ((directory.peek() != null) && (result == true) && (shutdown == false))
		{
			String downloadFilename = directory.poll().getFilename();
			System.out.println("");
			logger.info("[" + prop.getId() + "]" + " DOWNLOAD Start " + downloadFilename);

			LinkedList<String> fileData = new LinkedList<String>();
			String temp = "";

			String previousResponseEOL = rx.getResponseEOL();
			rx.setResponseEOL("<CR><LF>");
			rx.clearQueue();

			String outfilename2 = downloadPath + java.io.File.separator + downloadFilename;
			String outfilename1 = outfilename2 + ".hex";

			File outFile1 = new File(outfilename1);
			File outFile2 = new File(outfilename2);

			FileUtils.deleteQuietly(outFile2);

			result = tx.send("*TYPEHEX," + downloadFilename + "<CR>");

			if (result == true)
			{

				while ((waitForReply() == true) && (result == true))
				{
					temp = rx.responseQueue.poll();
					temp = temp.replace("CMD->", "");
					fileData.add(temp);
					result = tx.send("000<ACK>");
					if (temp.equals(":0000000000"))
					{
						break;
					}
				}

				if (result == true)
				{
					try
					{
						FileUtils.writeLines(outFile1, "UTF-8", fileData, utils.encodeControlChars("<CR><LF>"));
						LabellerIntelHex ohc = new LabellerIntelHex();
						ohc.convertFromIntelHex(outFile1, outFile2);
						FileUtils.deleteQuietly(outFile1);
					} catch (Exception ex)
					{
						result = false;
						logger.error("[" + prop.getId() + "]" + " DOWNLOAD Error " + ex.getMessage());
					}

					logger.info("[" + prop.getId() + "]" + " DOWNLOAD Complete " + downloadFilename);
					System.out.println("");

					// result = checkSuccess();
				}
			}
			rx.setResponseEOL(previousResponseEOL);
		}

		if (result == false)
		{
			logger.error("[" + prop.getId() + "]" + " DOWNLOAD `Failed");

		}

		System.out.println("");
		return result;
	}

	public boolean DIR_REMOTE(String VAL)
	{
		boolean result = false;

		directory.clear();
		rx.clearQueue();
		tx.send("*DIR," + VAL + ",SDT<CR>");
		waitForReply();

		utils.pause(250);
		String response = "";
		System.out.println("");
		logger.info("Directory Listing");
		logger.info("[" + prop.getId() + "] -----------------------------------------------------------------------");
		while (rx.responseQueue.size() > 0)
		{
			response = JUtility.replaceNullStringwithBlank(rx.responseQueue.poll());
			if (response.equals("SUCCESS"))
			{
				break;
			}
			if (response.equals("FAILED"))
			{
				break;
			}

			response = response.replace("CMD->", "");
			String[] DTS = response.split(",");
			LabellerFile file = new LabellerFile();
			file.setFilename(DTS[0]);
			file.setDateTime(DTS[2], DTS[3]);
			file.setSize(Long.valueOf(DTS[1]));
			directory.add(file);

			logger.info("[" + prop.getId() + "] - " + file.toString());
		}
		logger.info("[" + prop.getId() + "] -----------------------------------------------------------------------");
		logger.info("[" + prop.getId() + "]   " + directory.size() + " file(s).");
		System.out.println("");
		result = true;

		return result;
	}

	public boolean waitForReply()
	{
		boolean responseReceived = false;

		int timeout = waitRetries;

		int qSize = rx.responseQueue.size();

		boolean cont = true;

		while (cont)
		{
			if (qSize == rx.responseQueue.size())
			{
				// No Changes to queue - wait for timeout before assuming it's
				// stabalised.
				utils.pause(waitDelay);
				timeout--;
			} else
			{
				// Changes so reset timeout.
				timeout = waitRetries;
				qSize = rx.responseQueue.size();
			}

			if (timeout == 0)
			{
				if (rx.responseQueue.size() > 0)
				{
					logger.info("[" + prop.getId() + "]" + " **** STABALISED ****");
					cont = false;
				} else
				{
					logger.error("[" + prop.getId() + "]" + " **** TIMEOUT NO RESPONSE ****");
					cont = true;
				}
			}

		}

		if (rx.responseQueue.size() > 0)
		{
			timeout = waitRetries;
			responseReceived = true;
		}

		return responseReceived;
	}

	public boolean checkSuccess()
	{
		boolean success = true;
		int timeout = waitRetries;
		String response = "";
		waitForReply();
		while ((timeout > 0) && (rx.responseQueue.size() > 0))
		{
			response = JUtility.replaceNullStringwithBlank(rx.responseQueue.poll());
			if (response.equals("") == false)
			{
				if (response.equals("SUCCESS"))
				{
					System.out.println("");
					logger.info("[" + prop.getId() + "]" + " Result [" + response + "]");
					System.out.println("");
					success = true;
					timeout = waitRetries;
					// rx.clearQueue();
					break;
				}
				if (response.equals("FAILED"))
				{
					System.out.println("");
					logger.error("[" + prop.getId() + "]" + " Result [" + response + "]");
					System.out.println("");
					success = false;
					timeout = waitRetries;
					// rx.clearQueue();
					break;
				}

				timeout--;

				if (timeout == 0)
				{
					System.out.println("");
					System.out.println("");
					success = true;
					// rx.clearQueue();
					break;
				}
			}
		}

		return success;
	}

	public void disconnect()
	{

		while (tx.isAlive())
		{
			tx.shutdown();
		}
		System.out.println("");
		logger.info("[" + prop.getId() + "]" + " TX Shutdown Reason [" + tx.getStatusName() + "]");
		System.out.println("");

		while (rx.isAlive())
		{
			rx.shutdown();
		}
		System.out.println("");
		logger.info("[" + prop.getId() + "]" + " RX Shutdown Reason [" + tx.getStatusName() + "]");
		System.out.println("");

		logger.info("[" + prop.getId() + "]" + " Disconnected.");
	}

	public boolean connect()
	{
		boolean result = false;

		while ((connected == false) && (shutdown == false))
		{
			try
			{
				socket = new Socket();
				socket.connect(new InetSocketAddress(prop.getIpAddress(), prop.getPortNumber()), prop.getConnectTimeout());
				socket.setKeepAlive(true);

				System.out.println("");
				logger.info("[" + prop.getId() + "]" + " Connected.");
				System.out.println("");

				rx = new LabellerTCPIP_RX();
				rx.setName(prop.getId() + " RX");
				rx.setSocketConnection(socket);
				rx.setLabellerProperties(prop);
				rx.start();

				tx = new LabellerTCPIP_TX();
				tx.setName(prop.getId() + " TX");
				tx.setSocketConnection(socket);
				tx.setLabellerProperties(prop);
				tx.start();

				connected = true;
				result = true;

			} catch (IOException e)
			{
				utils.pause(500);
				logger.error("[" + prop.getId() + "]" + " Connect Error : " + e.getMessage());
				result = false;
			}
		}

		return result;
	}

	public void shutdown()
	{
		System.out.println("");
		logger.info("[" + prop.getId() + "]" + " Shutdown requested.");
		System.out.println("");
		shutdown = true;
	}

	public Labeller(LabellerProperties prop, String commandFile)
	{
		this.commandFile = commandFile;
		this.prop = prop;
		rx = new LabellerTCPIP_RX();
		rx.setLabellerProperties(prop);
		tx = new LabellerTCPIP_TX();
		tx.setLabellerProperties(prop);

	}

}
