/*
 * (c) Copyright 2011 Epimorphics Ltd.
 * All rights reserved.
 * [See end of file]
 */

package tx;

import java.nio.ByteBuffer ;
import java.util.HashMap ;
import java.util.HashSet ;
import java.util.Iterator ;
import java.util.Map ;
import java.util.Set ;

import org.openjena.atlas.logging.Log ;

import tx.journal.Journal ;
import tx.transaction.Transaction ;

import com.hp.hpl.jena.tdb.base.block.Block ;
import com.hp.hpl.jena.tdb.base.block.BlockMgr ;

public class BlockMgrJournal implements BlockMgr
{
    
    private BlockMgr blockMgr ; // read-only except during journal checkpoint.
    private Journal journal ;
    private Transaction transaction ;
    
    final private Set<Integer> readBlocks = new HashSet<Integer>() ;
    final private Set<Integer> iteratorBlocks = new HashSet<Integer>() ;
    final private Map<Integer, Block> writeBlocks = new HashMap<Integer, Block>() ;
    final private Map<Integer, Block> freedBlocks = new HashMap<Integer, Block>() ;
    private boolean closed = false ;
    
    public BlockMgrJournal(Transaction txn, BlockMgr underlyingBlockMgr, Journal journal)
    {
        reset(txn, underlyingBlockMgr, journal) ;
    }

    public Iterator<Block> updatedBlocks()  { return writeBlocks.values().iterator() ; }
    public Iterator<Block> freedBlocks()    { return freedBlocks.values().iterator() ; }

    /** Set, or reset, this BlockMgr.
     *  Enables it to be reused when already part of a datastructure. 
     */
    public void reset(Transaction txn, BlockMgr underlyingBlockMgr, Journal journal)
    {
        this.blockMgr = underlyingBlockMgr ;
        this.journal = journal ;
        this.transaction = txn ;
    }
    
    @Override
    public Block allocate(int blockSize)
    {
        checkIfClosed() ;

        // Might as well allocate now. 
        // This allocates the id.
        Block block = blockMgr.allocate(blockSize) ;
        
        // But we "copy" it by allocating ByteBuffer space.
        ByteBuffer byteBuffer = ByteBuffer.allocate(blockSize) ;
        block = new Block(block.getId(), byteBuffer) ;
        return block ;
    }

    @Override
    public Block getRead(int id)
    {
        checkIfClosed() ;
        Block block = localBlock(id) ;
        if ( block != null )
            return block ;
        
        block = blockMgr.getRead(id) ;
        readBlocks.add(block.getId()) ;
        return block ;
    }

    @Override
    public Block getReadIterator(int id)
    {
        checkIfClosed() ;
        Block block = localBlock(id) ;
        if ( block != null )
            return block ;
        
        block = blockMgr.getReadIterator(id) ;
        iteratorBlocks.add(block.getId()) ;
        return block ;
    }

    @Override
    public Block getWrite(int id)
    {
        checkIfClosed() ;
        Block block = localBlock(id) ;
        if ( block != null )
            return block ;
        
        // Get-as-read.
        block = blockMgr.getRead(id) ;
        // If most blocks get modified, then a copy is needed
        // anyway so now is as good a time as any.
        block = _promote(block) ;
        return block ;
    }

    private Block localBlock(int id)
    {
        checkIfClosed() ;
        return writeBlocks.get(id) ;
    }
    
    @Override
    public Block promote(Block block)
    {
        checkIfClosed() ;
        if ( writeBlocks.containsKey(block.getId()) )
            return block ;
        return _promote(block) ;
    }

    private Block _promote(Block block)
    {
        checkIfClosed() ;
        block = block.replicate() ;
        writeBlocks.put(block.getId(), block) ;
        return block ;
    }

    @Override
    public void release(Block block)
    {
        checkIfClosed() ;
        Integer id = block.getId() ;
        if ( readBlocks.contains(id) || iteratorBlocks.contains(id) )
            blockMgr.release(block) ;
    }

    @Override
    public void write(Block block)
    {
        checkIfClosed() ;
        if ( ! writeBlocks.containsKey(block.getId()) )
        {
            Log.warn(this, "Block not recognized: "+block.getId()) ;
            // Probably corruption by writing in-place.
            // but at least when this trasnaction commits,
            // the update data is written,
            writeBlocks.put(block.getId(), block) ;
        }
    }

    @Override
    public void free(Block block)
    {
        checkIfClosed() ;
        freedBlocks.put(block.getId(), block) ;
    }

    @Override
    public boolean isEmpty()
    {
        checkIfClosed() ;
        return writeBlocks.isEmpty() && blockMgr.isEmpty() ;
    }

    @Override
    public boolean valid(int id)
    {
        checkIfClosed() ;
        if ( writeBlocks.containsKey(id) ) return true ;
        return blockMgr.valid(id) ; 
    }

    @Override
    public void close()
    {
        closed = true ;
    }

    @Override
    public boolean isClosed()
    {
        return closed ;
    }
    
    private void checkIfClosed()
    {
        if ( closed )
            Log.fatal(this, "Already closed: "+transaction.getTxnId()) ;
    }

    @Override
    public void sync()
    {
        checkIfClosed() ;
    }

    @Override
    // Yes - read.
    public void beginUpdate()           { checkIfClosed() ; blockMgr.beginRead() ; }

    @Override
    public void endUpdate()             { checkIfClosed() ; blockMgr.endRead() ; }

    @Override
    public void beginRead()             { checkIfClosed() ; blockMgr.beginRead() ; }

    @Override
    public void endRead()               { checkIfClosed() ; blockMgr.endRead() ; }

    @Override
    public void beginIterator(Iterator<?> iterator)
    {
        checkIfClosed() ; 
        transaction.addIterator(iterator) ;
        blockMgr.beginIterator(iterator) ;
    }

    @Override
    public void endIterator(Iterator<?> iterator)
    {
        checkIfClosed() ; 
        transaction.removeIterator(iterator) ;
        blockMgr.endIterator(iterator) ;
    }
}

/*
 * (c) Copyright 2011 Epimorphics Ltd.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */