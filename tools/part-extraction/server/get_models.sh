# Downloads the Klein models from ModelScope, all four at once; curl -C - resumes a partial file.
M=/root/autodl-tmp/comfyui/models
H=https://modelscope.cn/models
get() { mkdir -p "$M/$1"; curl -sSL --retry 10 -C - -o "$M/$1/$2" "$H/$3" && echo "done $1/$2 $(stat -c %s "$M/$1/$2")"; }
get vae flux2-vae.safetensors Comfy-Org/flux2-dev/resolve/master/split_files/vae/flux2-vae.safetensors &
get diffusion_models flux-2-klein-base-4b.safetensors black-forest-labs/FLUX.2-klein-base-4B/resolve/master/flux-2-klein-base-4b.safetensors &
get text_encoders qwen_3_4b.safetensors Comfy-Org/z_image_turbo/resolve/master/split_files/text_encoders/qwen_3_4b.safetensors &
get diffusion_models flux-2-klein-4b.safetensors black-forest-labs/FLUX.2-klein-4B/resolve/master/flux-2-klein-4b.safetensors &
wait
echo all-done
