/* 
 * Copyright 2015-2024 (c) CoralBlocks LLC - http://www.coralblocks.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.coralblocks.coralring.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import sun.misc.Unsafe;
import sun.nio.ch.FileChannelImpl;

/**
 * This class employs many different reflection tricks and <code>sun.misc.Unsafe</code> to allocate, access and release native memory in Java through memory-mapped files.
 */
public class SharedMemory implements Memory {
	
	// Long.MAX_VALUE = 9,223,372,036,854,775,807 bytes
	// Long.MAX_VALUE = 8,388,608 terabytes
	// Let's not go overboard and set a max of 4,194,304 terabytes (half of MAX_VALUE)
	public static final long MAX_SIZE = Long.MAX_VALUE / 2L;
	
	private static Unsafe unsafe;
	private static boolean UNSAFE_AVAILABLE = false;
	private static boolean MAP_UNMAP_AVAILABLE = false;
	private static boolean ADDRESS_AVAILABLE = false;
	
	static {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			unsafe = (Unsafe) field.get(null);
			UNSAFE_AVAILABLE = true;
		} catch(Exception e) {
			// throw exception later when we try to allocate memory in the constructor
		}
    }
	
	private static boolean isNewSyncMap = false;
	private static boolean isJava21 = false;
	private static boolean isJava19 = false;
	private static Method mmap;
	private static Method unmmap;
	private static final Field addressField;
	
	private static Method getMethod(Class<?> cls, String name, Class<?>... params) throws Exception {
		Method m = cls.getDeclaredMethod(name, params);
		m.setAccessible(true);
		return m;
	}
 
	static {
		
		Field addrField = null;
		
		try {
			try {
				mmap = getMethod(FileChannelImpl.class, "map0", int.class, long.class, long.class);
				isNewSyncMap = false;
			} catch(Exception e) {
				try {
					mmap = getMethod(FileChannelImpl.class, "map0", int.class, long.class, long.class, boolean.class);
					isNewSyncMap = true;
				} catch(Exception ee) {
					mmap = getMethod(FileChannelImpl.class, "map", MapMode.class, long.class, long.class);
					isJava21 = true;
				}
			}
			
			try {
				unmmap = getMethod(FileChannelImpl.class, "unmap0", long.class, long.class);
				isJava19 = true;
			} catch(Exception e) {
				unmmap = getMethod(FileChannelImpl.class, "unmap", MappedByteBuffer.class);
				isJava21 = true;
			}
			
			MAP_UNMAP_AVAILABLE = true;
			
			addrField = Buffer.class.getDeclaredField("address");
			addrField.setAccessible(true);
			
			ADDRESS_AVAILABLE = true;
			
		} catch (Exception e) {
			// throw exception later when we try to allocate memory in the constructor
		}
		
		addressField = addrField;
	}
	
	/**
	 * Returns true is this class can be used and is available in your platform
	 * 
	 * @return true if available
	 */
	public static boolean isAvailable() {
		return UNSAFE_AVAILABLE && MAP_UNMAP_AVAILABLE && ADDRESS_AVAILABLE;
	}

	private final long address;
	private final long size;
	private final MappedByteBuffer mbb;
	private final String filename;
	
	/**
	 * Creates a shared memory with the given size. The filename will be implied.
	 * 
	 * @param size the size of the memory
	 */
	public SharedMemory(long size) {
		this(size, createFilename(size));
	}
	
	/**
	 * Creates a shared memory with the given filename. The size will be implied from the file.
	 * 
	 * @param filename the name of the memory mapped file containing this memory
	 */
	public SharedMemory(String filename) {
		this(-1, filename);
	}
	
	/**
	 * Creates a shared memory with the given size and filename.
	 * 
	 * @param size the size of the memory or -1 to imply from the file
	 * @param filename the name of the file
	 */
	public SharedMemory(long size, String filename) {
		
		if (!UNSAFE_AVAILABLE) {
			throw new IllegalStateException("sun.misc.Unsafe is not accessible!");
		}
		
		if (!MAP_UNMAP_AVAILABLE) {
			throw new IllegalStateException("Cannot get map and unmap methods from FileChannel through reflection!");
		}
		
		if (!ADDRESS_AVAILABLE) {
			throw new IllegalStateException("Cannot get address field from Buffer through reflection!");
		}
		
		if (size == -1) {
			size = findFileSize(filename);
		} else if (size <= 0) {
			throw new IllegalArgumentException("Invalid size: " + size);
		}
		
		if (size > MAX_SIZE) throw new IllegalArgumentException("This size is not supported: " + size + " (MAX = " + MAX_SIZE + ")");
		
		this.size = size;
		
		try {
			
			int index = filename.lastIndexOf(File.separatorChar);
			
			if (index > 0) {
				String fileDir = filename.substring(0, index);
				File file = new File(fileDir);
				if (!file.exists()) {
					if (!file.mkdirs()) {
						throw new RuntimeException("Cannot create store dir: " + fileDir + " for " + filename);
					}
				}
			}

			this.filename = filename;
			RandomAccessFile file = new RandomAccessFile(filename, "rw");
			file.setLength(size);
			FileChannel fileChannel = file.getChannel();
			if (isJava21) {
				this.mbb = (MappedByteBuffer) mmap.invoke(fileChannel, MapMode.READ_WRITE, 0L, this.size);
				this.address = (long) addressField.get(this.mbb);
			} else if (isNewSyncMap) {
				this.address = (long) mmap.invoke(fileChannel, 1, 0L, this.size, false);
				this.mbb = null;
			} else {
				this.address = (long) mmap.invoke(fileChannel, 1, 0L, this.size);
				this.mbb = null;
			}
			fileChannel.close();
			file.close();
		} catch(Exception e) {
			throw new RuntimeException("Cannot mmap shared memory!", e);
		}
	}
	
	/**
	 * Return the size in bytes of the given file.
	 * 
	 * @param filename the name of the file
	 * @return the size in bytes of the file
	 */
	public static final long findFileSize(String filename) {
		File file = new File(filename);
		if (!file.exists()) throw new RuntimeException("File not found: " + filename);
		if (file.isDirectory()) throw new RuntimeException("File is a directory: " + filename);
		return file.length();
	}
	
	private static final String createFilename(long size) {
		if (size <= 0) throw new IllegalArgumentException("Cannot create file with this size: " + size);
		return SharedMemory.class.getSimpleName() + "-" + size + ".mmap";
	}
	
	/**
	 * Return the name of the file containing this memory.
	 * 
	 * @return the name of the file
	 */
	public String getFilename() {
		return filename;
	}
	
	@Override
	public long getSize() {
		return size;
	}
	
	@Override
	public long getPointer() {
		return address;
	}

	@Override
	public void release(boolean deleteFileIfUsed) {
		
		RuntimeException firstException = null;
		
		try {
			if (isJava19) { // isJava21 will be true here too
				unmmap.invoke(null, address, size);
			} else if (isJava21) {
				unmmap.invoke(null, this.mbb);
			} else {
				unmmap.invoke(null, address, size);
			}
		} catch(Exception e) {
			firstException = new RuntimeException("Cannot release mmap shared memory!", e);
			throw firstException;
		} finally {
			if (deleteFileIfUsed) deleteFile(firstException);
		}
	}
	
	private void deleteFile(Exception firstException) {
		Path path = Paths.get(filename);
        try {
            Files.deleteIfExists(path); // if someone else deleted it
        } catch (IOException e) {
        	RuntimeException exception = new RuntimeException("Failed to delete the file: " + filename, e);
        	if (firstException != null) {
        		exception.addSuppressed(firstException);
        	}
            throw exception;
        }
	}

	@Override
	public byte getByte(long address) {
		return unsafe.getByte(address);
	}

	@Override
	public byte getByteVolatile(long address) {
		return unsafe.getByteVolatile(null, address);
	}
 
	@Override
	public int getInt(long address) {
		return unsafe.getInt(address);
	}

	@Override
	public int getIntVolatile(long address) {
		return unsafe.getIntVolatile(null, address);
	}

	@Override
	public long getLong(long address) {
		return unsafe.getLong(address);
	}
	
	@Override
	public long getLongVolatile(long address) {
		return unsafe.getLongVolatile(null, address);
	}
	
	@Override
	public void putByte(long address, byte val) {
		unsafe.putByte(address, val);
	}
	
	@Override
	public void putByteVolatile(long address, byte val) {
		unsafe.putByteVolatile(null, address, val);
	}

	@Override
	public void putInt(long address, int val) {
		unsafe.putInt(address, val);
	}

	@Override
	public void putIntVolatile(long address, int val) {
		unsafe.putIntVolatile(null, address, val);
	}

	@Override
	public void putLong(long address, long val) {
		unsafe.putLong(address, val);
	}
	
	@Override
	public void putLongVolatile(long address, long val) {
		unsafe.putLongVolatile(null, address, val);
	}

	@Override
	public short getShort(long address) {
		return unsafe.getShort(null, address);
	}

	@Override
	public void putShort(long address, short value) {
		unsafe.putShort(null, address, value);
	}

	@Override
	public short getShortVolatile(long address) {
		return unsafe.getShortVolatile(null, address);
	}

	@Override
	public void putShortVolatile(long address, short value) {
		unsafe.putShortVolatile(null, address, value);
	}

	@Override
	public void putByteBuffer(long address, ByteBuffer src, int len) {
		if (!src.isDirect()) {
			throw new RuntimeException("putByteBuffer can only take a direct byte buffer!");
		}
		try {
			long srcAddress = (long) addressField.get(src);
			unsafe.copyMemory(srcAddress, address, len);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void getByteBuffer(long address, ByteBuffer dst, int len) {
		if (!dst.isDirect()) {
			throw new RuntimeException("getByteBuffer can only take a direct byte buffer!");
		}
		try {
			long dstAddress = (long) addressField.get(dst);
			dstAddress += dst.position();
			unsafe.copyMemory(address, dstAddress, len);
			dst.position(dst.position() + len);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void putByteArray(long address, byte[] src, int len) {
		unsafe.copyMemory(src, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, address, len);
	}

	@Override
	public void getByteArray(long address, byte[] dst, int len) {
		unsafe.copyMemory(null, address, dst, Unsafe.ARRAY_BYTE_BASE_OFFSET, len);
	}
	
}