package hum.bosco.trade.quik.adapter;

import hum.bosco.trade.quik.dealmakers.QuikAndTickerProperties;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.NumberFormat;
import java.text.ParseException;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

public class QuikCommandPipeAdapter implements Closeable {
	private HANDLE pipeHandle = WinNT.INVALID_HANDLE_VALUE;
	PipesAPI kernel32;
	
	public QuikCommandPipeAdapter(){
		// проблем с загрузкой ядра вообще-то быть не должно.
		System.setProperty("jna.encoding", "Cp1251");
		kernel32 = (PipesAPI) Native.loadLibrary("kernel32", PipesAPI.class, W32APIOptions.UNICODE_OPTIONS);
	}

	private void forceDisconnect(){
		if (pipeHandle != WinNT.INVALID_HANDLE_VALUE){
			kernel32.CloseHandle(pipeHandle);
			pipeHandle = WinNT.INVALID_HANDLE_VALUE;
			//System.out.println("Закрыли трубу!");
		}
	}
	private boolean ensureConnected(){
		if (pipeHandle != WinNT.INVALID_HANDLE_VALUE)
			return true;
		
		int retryCount = 3;
		while (pipeHandle == WinNT.INVALID_HANDLE_VALUE){
			pipeHandle = kernel32.CreateFile(
					"\\\\.\\pipe\\pmb.quik.pipe", 
					Kernel32.GENERIC_READ | Kernel32.GENERIC_WRITE, 
					0, 
					null, 
					Kernel32.OPEN_EXISTING, 
					0, 
					null);
			int errorCode = kernel32.GetLastError();
			if ((errorCode == 2 || errorCode == 231) && --retryCount < 0) // трубы нет или занята
				return false;			
		}
		//System.out.println("Готово!");		
		//DWORD mode = PipesAPI.PIPE_READMODE_MESSAGE;
		//kernel32.SetNamedPipeHandleState(pipeHandle, new DWORDByReference(mode), null, null);
		//System.out.println(kernel32.GetLastError());
		if (kernel32.GetLastError() != Kernel32.ERROR_SUCCESS){
			forceDisconnect();
			return false;
		}
		return true;
	}
	
	public String executeRequest(String command, boolean doCloseConnection){
		try{
			//System.out.println("Соединяемся");
			if (ensureConnected()){
				//String command = "stm";//"isc";//"staEQBREMU:SBER03";
				WinBase.OVERLAPPED overlapped = new WinBase.OVERLAPPED();
				ByteBuffer commandBytes = ByteBuffer.allocate(command.length()+1);
				commandBytes.put(command.getBytes()).put((byte)0);
				kernel32.WriteFile(pipeHandle, commandBytes.array(), commandBytes.capacity(), new IntByReference(), 	overlapped);
				//System.out.println(kernel32.GetLastError());
				kernel32.FlushFileBuffers(pipeHandle);
				//System.out.println(kernel32.GetLastError());
				while(overlapped.Internal.intValue() == WinNT.ERROR_IO_PENDING)
					;
		
				//System.out.println("Записали");
				ByteBuffer buffer = ByteBuffer.allocate(4*1024);
				IntByReference bytesRead = new IntByReference(buffer.capacity()); 
				int lastError = 0;
				//System.out.println("Начали читать..");
				while (!(kernel32.ReadFile(pipeHandle, buffer, buffer.capacity(), bytesRead, null)) 
						|| (lastError=kernel32.GetLastError()) == Kernel32.ERROR_MORE_DATA){
					// читаем и читаем
					if (lastError == Kernel32.ERROR_PIPE_NOT_CONNECTED )
						break;
				}
				//System.out.println("Считали: " + bytesRead.getValue() + " байт");
				if (doCloseConnection){
					forceDisconnect();
				}
				String result = new String(buffer.array(), 0, bytesRead.getValue());
				//System.out.println("Quik pipe -> : " + result);
				return result;
			} else{
				//System.out.println("Quik Pipe cоединение не может быть установлено. Вероятно сервер выключен");
				return null;
			}
		} finally {
			if (doCloseConnection){
				forceDisconnect();
			}
		}
	}
	
	public boolean isConnectedToServer(boolean doCloseConnection){
		return "1".equals(executeRequest("isc", doCloseConnection));
	}
	
	public String getLastCandlesOf(String classCode, String securityCode, Interval interval, int numberOfCandles, boolean doCloseConnection){
		return executeRequest("sve"+classCode + ":" + securityCode + ":" + interval.code + ":"+ numberOfCandles, doCloseConnection);
	}
	
	public String getServerCurrentHour(boolean doCloseConnection){
		String s = executeRequest("stm", doCloseConnection);
		if (s!=null && s.length()>2)
			return s.substring(0, 2);
		return "";
	}
	public String getServerCurrentTime(boolean doCloseConnection){
		String s = executeRequest("stm", doCloseConnection);
		if (s!=null)
			return s;
		return "";
	}
	
	public String getTradeDate(boolean doCloseConnection){
		return executeRequest("trd", doCloseConnection);
	}
	
	public double getContractPrice(String classCode, String securityCode, boolean doCloseConnection){
		String contract = executeRequest("go " + classCode + ":" + securityCode, doCloseConnection);
		if (contract == null || contract.length()<1){
			return 0;
		}
		return Double.valueOf(contract.replaceAll("[^0-9,\\.]", "").replaceAll(",", "."));
	}
	
	@Override
	public void close() throws IOException {
		forceDisconnect();
	}
	public enum Interval{
		MINUTE(1),  HOUR(60),  DAY(1440), 
				WEEK(10080), MONTH(23200);
	    private final int code;

	    private Interval(int code) {
	        this.code = code;
	    }
	}
			

	public static void main(String s[]) throws IOException{
		try(QuikCommandPipeAdapter adapter = new QuikCommandPipeAdapter()) {
			long started = System.currentTimeMillis();
			System.out.println(adapter.isConnectedToServer(false));
			System.out.println(adapter.getTradeDate(false));
			System.out.println(adapter.executeRequest("staSPBFUT:Si-12.14", false));
			System.out.println(adapter.getServerCurrentTime(false).replaceAll(":", ""));
			System.out.println("ГО : " + adapter.getContractPrice("SPBFUT", "Si-12.14", false));
//			adapter.getTradeDate(false);
//			adapter.getServerCurrentHour(false);
			System.out.println(adapter.getLastCandlesOf(QuikAndTickerProperties.current.classCode, QuikAndTickerProperties.current.tiker, Interval.HOUR, 50, false));
			System.out.println("На всё ушло: " + (System.currentTimeMillis() - started));
		}
	}
}
