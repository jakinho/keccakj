package com.github.aelstad.keccakj.core;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A cryptographic random generator based on Keccack-1600 
 * and the duplex construction. Supports re-seeding and 
 * forward secrecy through forgetting past state.
 * 
 * Note that this generator must be synchronized externally 
 * if used concurrently from multiple threads.
 *
 */
public final class DuplexRandom {
	class ForgettingByteArrayOutputStream extends ByteArrayOutputStream {		
		public void forget() {
			Arrays.fill(buf, (byte) 0);
			reset();
		}

		public byte[] getBuf() {
			return buf;
		}
	}
			
	private final int MIN_SEED_LENGTH_BYTES = 16; // 128 bits	
	
	private Keccack1600 keccack1600;
	private int rateBytes;	
	private int pos;
	
	private boolean seeded;
	
	private ForgettingByteArrayOutputStream duplexIn = new ForgettingByteArrayOutputStream();
	
	// static master seed generator
	private final static DuplexRandom seedGenerator;
	
	static {
		// seed the master seed-generator with 512 bits of random entropy
		seedGenerator = new DuplexRandom(1085);
		byte[] seed = SecureRandom.getSeed(64);
		seedGenerator.feed(seed, 0, seed.length);
		Arrays.fill(seed, (byte) 0);
		seed = null;
	}
	
	/**
	 * @param capacityInBits. This should be approximately 2x the desired 
	 * security level. For efficiency it should be chosen such that 
	 * we get a multiple of 64-bytes output per permutation. This  
	 * generator used 3 bits of padding so pick capcitiyInBits = n*64 - 3 
	 * with n between [4, 25>. Higher n gives higher security, but lower 
	 * performance. 
	 */
	public DuplexRandom(int capacityInBits) {
		this.keccack1600 = new Keccack1600(capacityInBits);
		this.rateBytes = (keccack1600.getRateBits()-3)>>3;
		this.seeded = false;
	}
	
	/**
	 * (Re)seed with the supplied seed. If you seed with a static 
	 * value before the first call to getBytes() you will get 
	 * a reproducible random sequence with a security that depends upon
	 * the security of the seed. Seeds are accumulated and not used
	 * before you have supplied at least 16 bytes/128 bits. 
	 *  
	 * @param seed
	 * @param off
	 * @param len
	 */
	public void seed(byte[] seed, int off, int len) {
		if((duplexIn.size()+len) >= MIN_SEED_LENGTH_BYTES) {			
			feed(duplexIn.getBuf(), 0, duplexIn.size());
			feed(seed, off, len);
			duplexIn.forget();		
		} else {
			duplexIn.write(seed, off, len);
		}
	}
	
	/**
	 * (Re)seed using the internal seed generator
	 * 
	 */
	public void seed() {
		int seedLength = Math.max((keccack1600.getCapacitityBits()>>4) + 1, MIN_SEED_LENGTH_BYTES);
		byte[] seed = new byte[seedLength];
		synchronized(seedGenerator) {				
			seedGenerator.getBytes(seed, 0, seed.length);
			seedGenerator.forget();
		} 
		feed(seed, 0, seed.length);
		Arrays.fill(seed, (byte) 0);
		seed = null;		
	}
	
	
	/**
	 * Get seed bytes from the master seed generator
	 * 
	 * @param buf
	 * @param off
	 * @param len
	 */
	public static void getSeedBytes(byte[] buf, int off, int len) {
		synchronized (seedGenerator) {
			seedGenerator.getBytes(buf, off, len);
			seedGenerator.forget();
		}
	}
	
	
	/**
	 * Generate random bytes
	 */	
	public void getBytes(byte[] buf, int off, int len) {
		if(!seeded) {
			seed();
		}
		while(len > 0) {
			int chunk = Math.min(len, rateBytes - pos);
			if(chunk == 0) {
				// Output: Append 0-bit and pad 
				keccack1600.pad((byte) 0, 1, 0);
				keccack1600.permute();
				pos = 0;
				continue;
			}
			keccack1600.getBytes(pos, buf, off, chunk);
			off += chunk;
			len -= chunk;			
			pos += chunk;
		}								
	}	
	
	/**
	 * Feed seed material 
	 * 
	 * @param buf
	 * @param off
	 * @param len
	 */
	private void feed(byte[] buf, int off, int len) {
		pos = 0;
		while(len > 0) {
			int chunk = Math.min(len, rateBytes - pos);
			keccack1600.setXorBytes(pos, buf, off, chunk);
			off += chunk;
			len -= chunk;			
			pos += chunk;

			if(chunk == 0 || len == 0) {
				// Consume seed material: Append 1-bit pad and permute
				keccack1600.pad((byte)1, 1, 0);
				keccack1600.permute();
				pos = 0;
			}
		}
		seeded = true;
	}
	
	
	/**
	 * Forget state providing forward secrecy
	 */	
	public void forget() {
		pos = 0;
		keccack1600.pad(pos<<3);
		keccack1600.permute();
		
		keccack1600.zeroBytes(0, rateBytes);
		keccack1600.pad(rateBytes<<3);		
		keccack1600.permute();		
	}
}
