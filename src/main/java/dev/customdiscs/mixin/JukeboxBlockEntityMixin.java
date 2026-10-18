package dev.customdiscs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import dev.customdiscs.server.JukeboxTracker;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private static void customDiscs$onTick(Level level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox, CallbackInfo ci) {
		JukeboxTracker.onJukeboxTick(level, pos, state, jukebox);
	}
}
