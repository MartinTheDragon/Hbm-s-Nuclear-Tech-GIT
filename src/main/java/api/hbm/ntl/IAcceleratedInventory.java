package api.hbm.ntl;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

public interface IAcceleratedInventory {
	static void transferItemsBetweenInventories(IInventory from, IInventory to, int amount) {
		int remaining = amount;
		for(int originSlot = 0; originSlot < from.getSizeInventory(); originSlot++) {
			ItemStack impostor = from.getStackInSlot(originSlot);
			if(impostor == null) continue;
			impostor = impostor.splitStack(Math.min(remaining, impostor.stackSize));
			int originalCount = impostor.stackSize;
			for(int destinationSlot = 0; destinationSlot < to.getSizeInventory(); destinationSlot++) {
				if(!to.isItemValidForSlot(destinationSlot, impostor)) continue;
				if(remaining <= 0) return;

			}
		}
	}
}
