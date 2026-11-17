                args.add("X11_WND_GPU_INFO=1");
                // VirGL gives the guest OpenGL and no Vulkan, so DXVK cannot create a device. Direct3D 9
                // and older go through Wine's own OpenGL renderer instead, which is the path that
                // actually renders here; this is also what DirectX 9 games such as TrackMania need.
                args.add("PROTON_USE_WINED3D=1");
                args.add("WINEDLLOVERRIDES=d3d9,d3d10core,d3d11,dxgi=b");
                // Wine's debug channels are not free: every fixme and trace line costs CPU time in the
                // hot path of an emulated game. -all turns them off; real errors still reach message
                // boxes and the process exit code, so behavior stays visible without the cost.
                args.add("WINEDEBUG=-all");
                // Eventfd-based synchronisation removes a lot of wineserver round trips per frame. Wine
                // falls back on its own when the kernel or PRoot refuses it, so this is safe to ask for.
                args.add("WINEESYNC=1");
                // Old Direct3D titles push x87 math through the translator; reduced precision converts
                // it to plain SSE work instead of emulating 80-bit sequences per instruction.
                args.add("FEX_X87REDUCEDPRECISION=1");
