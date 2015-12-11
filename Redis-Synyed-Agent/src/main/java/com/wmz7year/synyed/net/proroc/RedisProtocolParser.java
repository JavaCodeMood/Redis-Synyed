package com.wmz7year.synyed.net.proroc;

import static com.wmz7year.synyed.constant.RedisProtocolConstant.*;
import static com.wmz7year.synyed.constant.RedisCommandSymbol.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.wmz7year.synyed.exception.RedisProtocolException;
import com.wmz7year.synyed.packet.redis.RedisDataBaseTransferPacket;
import com.wmz7year.synyed.packet.redis.RedisErrorPacket;
import com.wmz7year.synyed.packet.redis.RedisPacket;
import com.wmz7year.synyed.packet.redis.RedisSimpleStringPacket;

/**
 * Redis数据管道解析器<br>
 * 将socket中读取的byte数据流进行初步处理<br>
 * 如断包、粘包等<br>
 * 每个redis命令都以\r\n结尾 也就是0x0D 0x0A<br>
 * 该解析器为全局唯一的对象
 * 
 * 
 * @Title: RedisProtocolParser.java
 * @Package com.wmz7year.synyed.parser
 * @author jiangwei (ydswcy513@gmail.com)
 * @date 2015年12月10日 下午2:57:26
 * @version V1.0
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class RedisProtocolParser {
	/**
	 * 缓冲区大小 1M
	 */
	private int BUFFERSIZE = 1024 * 1024;
	/**
	 * 缓冲区
	 */
	private byte[] buffer = new byte[BUFFERSIZE];
	/**
	 * 缓冲区中写入的位置
	 */
	private int limit = 0;
	/**
	 * 缓冲区读取过的位置
	 */
	private int readFlag = 0;
	/**
	 * 缓冲区最大长度
	 */
	private int maxLength = buffer.length;

	/**
	 * 判断缓冲区是否写满的标志位
	 */
	private boolean isFull = false;
	/**
	 * 当前解析中的数据包
	 */
	private byte[] currentPacket;
	/**
	 * 当前处理中的数据包写入位置
	 */
	int currentPacketWriteFlag = 0;
	/**
	 * 读取数据包的自动扩容数量
	 */
	private int readInc = 128;
	/**
	 * 复合类型字符串长度是否读取过的标识为<br>
	 * 只在复合类型字符串响应数据时才使用
	 */
	private boolean bulkCrLfReaded = false;
	/**
	 * 复合类型字符串长度是否为正数的标识为<br>
	 * 0为未读取 1为正数 -1为负数
	 */
	private long bulkNeg = 0;
	/**
	 * 复合类型字符串的长度
	 */
	private long bulkLength = 0;
	/**
	 * 已经读取的复合类型字符串的长度
	 */
	private long readedBulkLength = 0;
	/**
	 * 解析出的数据包列表
	 */
	private List<RedisPacket> packets = new ArrayList<RedisPacket>();
	/**
	 * 当前数据包类型
	 */
	private byte currentPacketType;

	/**
	 * 解析Redis数据包的方法<br>
	 * 
	 * @param byteBuffer
	 *            需要解析的数据包内容
	 * @throws RedisProtocolException
	 *             当解析过程中出现问题则抛出该异常
	 */
	public void read(ByteBuffer byteBuffer) throws RedisProtocolException {
		// 获取数据包内容的长度
		int dataLength = byteBuffer.limit();
		// 创建对应大小的缓冲区
		byte[] dataBuffer = new byte[dataLength];
		// 读取数据
		byteBuffer.get(dataBuffer);
		System.out.println(java.util.Arrays.toString(dataBuffer));
		// 获取当前缓冲区剩余可用空间
		int currentCapacity = 0;
		if (limit > readFlag) {
			currentCapacity = (maxLength - limit) + readFlag;
		} else if (limit == readFlag) {
			currentCapacity = maxLength;
		} else {
			currentCapacity = maxLength - ((maxLength - readFlag) + limit);
		}

		// 判断缓冲区容量是否可以装得下此次来的数据 如果不能则扩充数组长度
		if (currentCapacity < dataLength) {
			byte[] tempBuffer = new byte[maxLength + (dataLength - currentCapacity)];
			if (limit > readFlag) { // 判断是否是连续拷贝
				System.arraycopy(buffer, readFlag, tempBuffer, 0, (limit - currentCapacity));
				limit = limit - readFlag; // 重置写入标识位
				maxLength = buffer.length;
			} else if (limit == readFlag) { // 读取与写入一样 重置缓冲区
				limit = 0; // 重置写入标识位
				maxLength = buffer.length;
			} else { // 分开拷贝
				System.arraycopy(buffer, readFlag, tempBuffer, 0, (maxLength - readFlag));
				System.arraycopy(buffer, 0, tempBuffer, (maxLength - readFlag), limit);
				maxLength = buffer.length;
			}
			buffer = null;
			buffer = tempBuffer;
			tempBuffer = null;
			readFlag = 0; // 复位读取的地方
		}

		// 判断是否需要中断复制
		if ((maxLength - limit) < dataLength) {
			// 分段的长度
			int flag = (maxLength - limit);
			// 第一次复制 从可以写入位置一直复制到数组结束
			System.arraycopy(dataBuffer, 0, buffer, limit, flag);
			// 第二次复制 从第一个元素开始写直到写完
			System.arraycopy(dataBuffer, flag, buffer, 0, dataLength - flag);
			limit = dataLength - flag;
			isFull = (limit == readFlag); // 判断是否为写满
		} else {
			System.arraycopy(dataBuffer, 0, buffer, limit, dataLength);
			// 更新limit位置
			limit = dataLength + limit;
			isFull = (limit == readFlag); // 判断是否为写满
		}
		dataBuffer = null;

		// 开始解析数据包
		while (true) {
			// 判断数据是否读取完了
			if (!hasData()) {
				break;
			}
			// 判断是否有解析中的包 如果没有则初始化一个新的当前包缓冲区
			if (currentPacket == null) {
				currentPacket = new byte[readInc];
				// 读取第一个字节 判断类型
				currentPacketType = buffer[readFlag++];
			}

			// 解析响应数据包内容
			RedisPacket responsePacket = null;
			if (currentPacketType == REDIS_PROTOCOL_SIMPLE_STRING) {
				responsePacket = processSimpleStringPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_BULK_STRINGS) {
				responsePacket = processBulkStringsPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_ARRAY) {
				// TODO 数组类型
			} else if (currentPacketType == REDIS_PROTOCOL_INTEGERS) {
				// TODO
			} else if (currentPacketType == REDIS_PROTOCOL_ERRORS) {
				responsePacket = processErrorPacket();
			} else {
				throw new RedisProtocolException("未知的数据包类型：" + currentPacketType);
			}
			if (responsePacket != null) {
				this.packets.add(responsePacket);
				// 清空当前处理的数据包
				cleanCurrentPacket();
				// 清空当前数据包类型
				this.currentPacketType = 0;
				// 清空当前读取的数据包长度 bulk专用
				this.readedBulkLength = 0;
				// 清空当前数据包长度 bulk专用
				this.bulkLength = 0;
				// 还原bulk数据包长度正负标识位
				this.bulkNeg = 0;
				// 清空crlf标识位
				this.bulkCrLfReaded = false;
			}
		}
	}

	/**
	 * 清空当前处理中的数据包的方法
	 */
	private void cleanCurrentPacket() {
		currentPacket = null;
		currentPacketWriteFlag = 0;
	}

	/**
	 * 读取字符串类型数据包的方法
	 * 
	 * @return 字符串类型数据包
	 */
	private RedisPacket processSimpleStringPacket() {
		// 读取数据
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		// 转换为数据包对象
		RedisSimpleStringPacket simpleStringPacket = new RedisSimpleStringPacket(new String(packetData));
		return simpleStringPacket;
	}

	/**
	 * 读取复合类型Redis字符串响应的方法
	 * 
	 * @return 复合类型字符串响应数据包
	 */
	private RedisPacket processBulkStringsPacket() {
		// 读取复合类型字符串数据长度
		long result = readBulkStringLength();
		if (result == 0) {
			return null;
		}

		// 判断是否是数据文件传输
		boolean isDatabaseTrancefer = false;
		// 如果数据长度大于5 则判断是否是Redis数据文件的传输
		if (result > 5) {
			if (readFlag + 5 < limit) {
				byte b1 = buffer[readFlag + 0];
				byte b2 = buffer[readFlag + 1];
				byte b3 = buffer[readFlag + 2];
				byte b4 = buffer[readFlag + 3];
				byte b5 = buffer[readFlag + 4];
				if (b1 == 'R' && b2 == 'E' && b3 == 'D' && b4 == 'I' && b5 == 'S') {
					isDatabaseTrancefer = true;
				} else {
					isDatabaseTrancefer = false;
				}
			} else {
				isDatabaseTrancefer = false;
			}
		} else {
			isDatabaseTrancefer = false;
		}

		// 判断校验结果
		if (isDatabaseTrancefer) {
			// 解析数据文件传输包
			return processBulkDatabaseTransferPacket();
		} else {
			System.out.println("普通 bulk字符串");
			return null;
		}

	}

	/**
	 * 处理Redis 文件传输格式数据包的方法
	 * 
	 * @return Redis数据文件传输格式数据包
	 */
	private RedisPacket processBulkDatabaseTransferPacket() {
		// 读取对应字节长度的数据
		while (true) {
			// 判断数据是否读取完了
			if (!hasData()) {
				break;
			}
			byte b = buffer[readFlag++];
			readedBulkLength++;
			if (readedBulkLength == bulkLength) {
				appendToCurrentPacket(b);
				// 数据读取完了
				break;
			} else {
				appendToCurrentPacket(b);
			}
		}
		// 判断数据是否读取完 如果读取完则解析完整的包
		if (readedBulkLength == bulkLength) {
			byte[] packetData = new byte[currentPacketWriteFlag];
			System.arraycopy(currentPacket, 0, packetData, 0, currentPacketWriteFlag);

			// 这就是完整的包了
			RedisDataBaseTransferPacket packet = new RedisDataBaseTransferPacket(DATABASETRANSFER);
			packet.setData(packetData);
			return packet;
		} else {
			// TODO
			System.out.println("这是ping报");
			return null;
		}
	}

	/**
	 * 读取redis复合类型字符串长度的方法
	 * 
	 * @return 复合类型字符串长度
	 */
	private long readBulkStringLength() {
		// 判断是否读取过长度信息
		if (!bulkCrLfReaded) {
			// 判断是否读取过数据的正负符号
			if (bulkNeg == 0) {
				if (!hasData()) {
					return 0;
				}
				byte isNegByte = buffer[readFlag++];
				boolean isNeg = isNegByte == '-';
				if (isNeg) {
					bulkNeg = -1;
				} else {
					appendToCurrentPacket(isNegByte);
					bulkNeg = 1;
				}
			}
			// 读取数据
			readData();
			// 获取完整数据包
			byte[] packetData = completCurrentPacket();
			if (packetData == null) {
				return 0;
			}
			// 解析数据长度
			for (int i = 0; i < packetData.length; i++) {
				bulkLength = bulkLength * 10 + packetData[i] - '0';
			}
			bulkCrLfReaded = true;
			// 清空长度数据包读取信息
			cleanCurrentPacket();
			currentPacket = new byte[readInc];
		}
		return bulkLength;
	}

	/**
	 * 读取错误类型数据包的方法
	 * 
	 * @return 错误类型数据包
	 */
	private RedisPacket processErrorPacket() {
		// 读取数据
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		RedisErrorPacket errorPacket = new RedisErrorPacket(new String(ERR));
		errorPacket.setErrorMessage(new String(packetData));
		return errorPacket;
	}

	/**
	 * 读取一行数据的方法<br>
	 * 以\r\n为结尾 数据读取到currentPacket中
	 */
	private void readData() {
		while (true) {
			// 判断数据是否读取完了
			if (!hasData()) {
				// 数据读完了 但是还没有数据包
				return;
			}
			// 读取一个字节
			byte b = buffer[readFlag++];
			if (b == REDIS_PROTOCOL_R) {
				if (hasData()) {
					byte b2 = buffer[readFlag++];
					if (b2 == REDIS_PROTOCOL_N) {
						// 一个完整的包
						appendToCurrentPacket(b);
						appendToCurrentPacket(b2);
						break;
					} else {
						// 追加数据
						appendToCurrentPacket(b2);
					}
				}
			} else {
				// 追加数据
				appendToCurrentPacket(b);
			}
		}
	}

	/**
	 * 将当前数据包处理缓冲区数据清除多余空间的方法
	 * 
	 * @return 当前数据包的数据
	 */
	private byte[] completCurrentPacket() {
		byte[] result = new byte[currentPacketWriteFlag - 2];
		System.arraycopy(currentPacket, 0, result, 0, currentPacketWriteFlag - 2);
		if (currentPacket[currentPacketWriteFlag - 2] == REDIS_PROTOCOL_R
				&& currentPacket[currentPacketWriteFlag - 1] == REDIS_PROTOCOL_N) {
			return result;
		}
		return null;
	}

	/**
	 * 将数据追加到当前处理的数据包的方法<br>
	 * 自动检测缓冲区容量 判断是否需要扩容
	 * 
	 * @param b
	 *            需要追加的数据
	 */
	private void appendToCurrentPacket(byte b) {
		if (currentPacketWriteFlag == currentPacket.length) {
			// 扩容
			byte[] newBuffer = new byte[currentPacketWriteFlag + readInc];
			System.arraycopy(currentPacket, 0, newBuffer, 0, currentPacketWriteFlag);
			currentPacket = newBuffer;
		}
		currentPacket[currentPacketWriteFlag++] = b;
	}

	/**
	 * 判断当前缓冲区中是否还有数据的方法<br>
	 * 判断依据为缓冲区的读取位与写入位是否一样
	 * 
	 * @return true为有数 false为没数据
	 */
	private boolean hasData() {
		return readFlag != limit;
	}

	/**
	 * 获取解析到的消息内容列表的方法
	 * 
	 * @return 消息内容列表
	 */
	public RedisPacket[] getPackets() {
		// 如果没解析出消息啧返回空数组
		if (packets.size() == 0) {
			return null;
		}
		RedisPacket[] redisPackets = packets.toArray(new RedisPacket[packets.size()]);
		// 执行内存回收操作
		packets.clear();
		gc();

		return redisPackets;
	}

	/**
	 * 当缓冲区超过默认大小时进行内存回收<br>
	 * 同时对缓冲区内的数据进行整理
	 */
	private void gc() {
		// 判断缓冲区大小是否为默认大小
		if (maxLength == BUFFERSIZE) {
			return;
		}
		if (readFlag == maxLength && !isFull) {
			buffer = null; // 释放缓冲区内存
			buffer = new byte[BUFFERSIZE];
			limit = 0;
			readFlag = 0;
			maxLength = BUFFERSIZE;
		}
	}
}