package com.commander4j.prodLine;

import org.apache.logging.log4j.Logger;

import com.commander4j.autolab.AutoLab;
import com.commander4j.dataset.DataSet;
import com.commander4j.modbus.Modbus;
import com.commander4j.utils.JUnique;
import com.commander4j.utils.JWait;
import com.commander4j.zebra.Print;

public class ProdLine extends Thread
{

	private String modbusName;
	private String modbusIPAddress;
	private int modbusPortNumber;
	private int modbusTimeOut;
	private int modbusCoil;
	private boolean modbusPrintOnValue;
	private String prodLineName="";
	private String printerName;
	private String printerIPAddress;
	private int printerPortNumber;
	private boolean run = true;
	private JWait wait = new JWait();
	private String remoteDataSetPath="";
	private String uuid = "";
	private JUnique unique = new JUnique();
	public DataSet dataset1;
	public Print print1;
	private String ssccSequenceFilename;
	private Logger logger = org.apache.logging.log4j.LogManager.getLogger((ProdLine.class));
	
	public ProdLine(String prodLineName,String modbusName,String modbusIPAddress,int modbusPortNumber,int modbusCoil,int modbusTimeout,boolean modbusPrintOnValue,String printerName,String remoteDataSetPath,String ssccSequenceFilename)
	{
		this.prodLineName=prodLineName;
		this.modbusName = modbusName;
		this.modbusIPAddress = modbusIPAddress;
		this.modbusPortNumber = modbusPortNumber;
		this.modbusCoil = modbusCoil;
		this.modbusTimeOut = modbusTimeout;
		this.modbusPrintOnValue = modbusPrintOnValue;
		this.printerName = printerName;
		this.remoteDataSetPath = remoteDataSetPath;
		this.uuid = unique.getUniqueID();
		this.ssccSequenceFilename = ssccSequenceFilename;
				
		setName("ProdLine "+prodLineName);	
		logger.debug("["+getUuid()+"] {"+getName()+"} Instance Created.");
	}
	
	public void shutdown()
	{
		run = false;
	}
	

	public String getSsccSequenceFilename()
	{
		return ssccSequenceFilename;
	}

	public void setSsccSequenceFilename(String ssccSequenceFilename)
	{
		this.ssccSequenceFilename = ssccSequenceFilename;
	}

	public String getProdLineName()
	{
		return prodLineName;
	}

	public void setProdLineName(String prodLineName)
	{
		this.prodLineName = prodLineName;
	}

	public String getUuid()
	{
		return uuid;
	}

	public void setUuid(String uuid)
	{
		this.uuid = uuid;
	}
	
	public String getRemoteDataSetPath()
	{
		return remoteDataSetPath;
	}

	public void setRemoteDataSetPath(String remoteDataSetPath)
	{
		this.remoteDataSetPath = remoteDataSetPath;
	}

	public String getModbusName()
	{
		return modbusName;
	}

	public void setModbusName(String modbusName)
	{
		this.modbusName = modbusName;
	}

	public String getModbusIPAddress()
	{
		return modbusIPAddress;
	}

	public void setModbusIPAddress(String modbusIPAddress)
	{
		this.modbusIPAddress = modbusIPAddress;
	}

	public int getModbusPortNumber()
	{
		return modbusPortNumber;
	}

	public void setModbusPortNumber(int modbusPortNumber)
	{
		this.modbusPortNumber = modbusPortNumber;
	}

	public int getModbusTimeOut()
	{
		return modbusTimeOut;
	}

	public void setModbusTimeOut(int modbusTimeOut)
	{
		this.modbusTimeOut = modbusTimeOut;
	}

	public int getModbusCoil()
	{
		return modbusCoil;
	}

	public void setModbusCoil(int modbusAddress)
	{
		this.modbusCoil = modbusAddress;
	}

	public boolean isModbusPrintOnValue()
	{
		return modbusPrintOnValue;
	}

	public void setModbusPrintOnValue(boolean modbusPrintOnValue)
	{
		this.modbusPrintOnValue = modbusPrintOnValue;
	}

	public String getPrinterName()
	{
		return printerName;
	}

	public void setPrinterName(String printerName)
	{
		this.printerName = printerName;
	}

	public String getPrinterIPAddress()
	{
		return printerIPAddress;
	}

	public void setPrinterIPAddress(String printerIPAddress)
	{
		this.printerIPAddress = printerIPAddress;
	}

	public int getPrinterPortNumber()
	{
		return printerPortNumber;
	}

	public void setPrinterPortNumber(int printerPortNumber)
	{
		this.printerPortNumber = printerPortNumber;
	}

	public void run()
	{

		run = true;
		logger.debug("["+getUuid()+"] {"+getName()+"} Thread Started...");
		
		AutoLab.start_SSCC_Thread(getSsccSequenceFilename());
		
		dataset1 = new DataSet(getUuid(),getProdLineName(),getPrinterName(),getRemoteDataSetPath());
		dataset1.start();


		print1 = new Print(getUuid(),getPrinterName());
		print1.start();
		
		Modbus modbus1;
		modbus1 = new Modbus(getUuid(),getName()+" "+getModbusName(),getModbusIPAddress(),getModbusPortNumber(),getModbusTimeOut(),getModbusCoil(),isModbusPrintOnValue(),getSsccSequenceFilename());
		modbus1.start();
		
		
		while (run == true)
		{
			wait.oneSec();
		}
		
		try
		{
			int retries=0;
			while (modbus1.isAlive() && (retries<10))
			{
				modbus1.shutdown();
				wait.milliSec(250);
			}
			if (modbus1.isAlive())
			{
				modbus1.interrupt();
			}
		}
		catch (Exception ex1)
		{
			logger.debug("["+getUuid()+"] {"+getName()+"} Exception : "+ex1.getLocalizedMessage());
		}
		
		try
		{
			while (print1.isAlive())
			{
				print1.shutdown();
				wait.milliSec(250);
			}
		}
		catch (Exception ex1)
		{
			logger.debug("["+getUuid()+"] {"+getName()+"} Exception : "+ex1.getLocalizedMessage());
		}
		
		try
		{
			while (dataset1.isAlive())
			{
				dataset1.shutdown();
				wait.milliSec(250);
			}
		}
		catch (Exception ex1)
		{
			logger.debug("["+getUuid()+"] {"+getName()+"} Exception : "+ex1.getLocalizedMessage());
		}
		
		AutoLab.stop_SSCC_Thread(getSsccSequenceFilename());
		
		wait.manySec(1);
		
		logger.debug("["+getUuid()+"] {"+getName()+"} Thread Stopped...");
	}
	
}